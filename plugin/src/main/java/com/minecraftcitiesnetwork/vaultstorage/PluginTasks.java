package com.minecraftcitiesnetwork.vaultstorage;

import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.jetbrains.annotations.NotNull;

/**
 * The main-thread and database executors, and how work on them ends when the plugin stops.
 *
 * <p>A capture stores on the database executor and then removes the container on the main
 * thread. The server's scheduler refuses tasks from a disabled plugin and drops the ones it
 * already holds, so a stop between those two steps would leave the items in both places.
 * Main-thread tasks are therefore kept in a queue of this class's own: the scheduler only says
 * when to run the next one, and {@link #stop} runs what is left, together with the database
 * work it leads to, until both are idle.
 *
 * <p>The plugin finishes twice: before its modules stop, so their captures end while their
 * classes can still be loaded, and after, for what stopping them started.
 */
public final class PluginTasks {

    private final @NotNull Consumer<Runnable> nextTick;
    private final @NotNull ExecutorService pool;
    private final @NotNull ScheduledExecutorService timer;
    private final @NotNull Logger logger;
    private final Queue<Runnable> forMainThread = new ConcurrentLinkedQueue<>();
    private final AtomicInteger databaseTasks = new AtomicInteger();

    /**
     * @param nextTick runs a task on the main thread; may throw once the plugin is disabled
     * @param pool     the threads database work runs on
     * @param timer    counts down the pause before a delayed database task
     */
    public PluginTasks(@NotNull Consumer<Runnable> nextTick, @NotNull ExecutorService pool,
                       @NotNull ScheduledExecutorService timer, @NotNull Logger logger) {
        this.nextTick = nextTick;
        this.pool = pool;
        this.timer = timer;
        this.logger = logger;
    }

    /** Runs a task on the main thread: on a later tick, or in {@link #stop}. Rejects once stopped. */
    public @NotNull Executor mainThread() {
        return this::onMainThread;
    }

    /** Runs a blocking task on the database pool. Rejects once stopped. */
    public @NotNull Executor database() {
        return this::onDatabase;
    }

    /** Runs a database task after a pause. Rejects from the moment the plugin starts to stop. */
    public void later(@NotNull Duration pause, @NotNull Runnable task) {
        this.timer.schedule(() -> {
            try {
                onDatabase(task);
            } catch (RejectedExecutionException stoppedMeanwhile) {
                // stop() has reported the delayed tasks it dropped.
            }
        }, pause.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void onMainThread(@NotNull Runnable task) {
        if (this.pool.isShutdown()) {
            throw new RejectedExecutionException("VaultStorage has stopped");
        }
        this.forMainThread.add(task);
        try {
            this.nextTick.accept(this::runNext);
        } catch (RuntimeException disabled) {
            // The plugin is disabled: finish() runs the queue.
        }
    }

    private void runNext() {
        Runnable task = this.forMainThread.poll();
        if (task != null) {
            task.run();
        }
    }

    private void onDatabase(@NotNull Runnable task) {
        this.databaseTasks.incrementAndGet();
        try {
            // Counted down after the task has handed on whatever follows it, so that zero
            // database tasks and an empty main-thread queue together mean nothing is left.
            this.pool.execute(() -> {
                try {
                    task.run();
                } finally {
                    this.databaseTasks.decrementAndGet();
                }
            });
        } catch (RuntimeException rejected) {
            this.databaseTasks.decrementAndGet();
            throw rejected;
        }
    }

    /**
     * Finishes what has been started: runs queued main-thread tasks here and waits for database
     * tasks, each of which may lead to more of the other, until neither is left or the time is
     * up. More work is still accepted afterwards. Call on the main thread.
     *
     * @return whether everything finished
     */
    public boolean finish(@NotNull Duration patience) {
        int delayed = this.timer.shutdownNow().size();
        if (delayed > 0) {
            this.logger.warning(delayed + " delayed database task(s) were dropped at shutdown. A vault still"
                    + " claimed is released at the next start.");
        }
        long deadline = System.nanoTime() + patience.toNanos();
        while (true) {
            for (Runnable task = this.forMainThread.poll(); task != null; task = this.forMainThread.poll()) {
                try {
                    task.run();
                } catch (IllegalPluginAccessException disabled) {
                    // It asked the server for a timer or a listener, which a stopping plugin does not get.
                    this.logger.log(Level.FINE, "A task was cut short because VaultStorage is stopping.", disabled);
                } catch (RuntimeException ex) {
                    this.logger.log(Level.SEVERE, "A task failed while VaultStorage was stopping.", ex);
                }
            }
            if (this.databaseTasks.get() == 0 && this.forMainThread.isEmpty()) {
                return true;
            }
            if (System.nanoTime() > deadline) {
                return false;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /**
     * {@link #finish Finishes} what is left and closes both executors. Call on the main thread,
     * once.
     *
     * @return whether everything finished
     */
    public boolean stop(@NotNull Duration patience) {
        boolean finished = finish(patience);
        if (finished) {
            this.pool.shutdown();
        } else {
            this.logger.severe("Work was still running after " + patience.toSeconds() + " seconds and was"
                    + " abandoned. A capture caught between storing and removing leaves its items in both"
                    + " places.");
            this.pool.shutdownNow();
        }
        return finished;
    }
}
