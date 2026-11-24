package com.minecraftcitiesnetwork.vaultstorage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PluginTasksTest {

    private static final Duration PATIENCE = Duration.ofSeconds(5);

    /** The server's scheduler: holds tasks until ticked, drops them and refuses more once disabled. */
    private static final class FakeScheduler {
        final List<Runnable> held = new ArrayList<>();
        boolean disabled;

        void schedule(Runnable task) {
            if (this.disabled) {
                throw new IllegalStateException("plugin is disabled");
            }
            this.held.add(task);
        }

        void tick() {
            List<Runnable> due = List.copyOf(this.held);
            this.held.clear();
            due.forEach(Runnable::run);
        }

        void disable() {
            this.disabled = true;
            this.held.clear();
        }
    }

    private final FakeScheduler scheduler = new FakeScheduler();
    private final List<LogRecord> logged = new ArrayList<>();
    private final PluginTasks tasks = new PluginTasks(this.scheduler::schedule, Executors.newFixedThreadPool(2),
            Executors.newSingleThreadScheduledExecutor(), recordingLogger());
    private final List<String> ran = new ArrayList<>();

    private Logger recordingLogger() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                PluginTasksTest.this.logged.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return logger;
    }

    private List<Level> loggedLevels() {
        return this.logged.stream().map(LogRecord::getLevel).toList();
    }

    @Test
    void aMainThreadTaskRunsOnTheNextTick() {
        this.tasks.mainThread().execute(() -> this.ran.add("task"));
        Assertions.assertEquals(List.of(), this.ran);

        this.scheduler.tick();

        Assertions.assertEquals(List.of("task"), this.ran);
    }

    @Test
    void aTaskTheSchedulerDroppedWhenThePluginWasDisabledRunsAtStop() {
        this.tasks.mainThread().execute(() -> this.ran.add("queued before"));
        this.scheduler.disable();

        Assertions.assertTrue(this.tasks.stop(PATIENCE));

        Assertions.assertEquals(List.of("queued before"), this.ran);
    }

    @Test
    void aTaskHandedOverAfterThePluginWasDisabledRunsAtStop() {
        this.scheduler.disable();
        this.tasks.mainThread().execute(() -> this.ran.add("queued after"));

        Assertions.assertTrue(this.tasks.stop(PATIENCE));

        Assertions.assertEquals(List.of("queued after"), this.ran);
    }

    @Test
    void stopFollowsAChainFromTheDatabaseToTheMainThreadAndBack() {
        this.scheduler.disable();
        Thread caller = Thread.currentThread();
        List<Thread> mainThreadSteps = new ArrayList<>();
        CompletableFuture<String> chain = CompletableFuture
                .supplyAsync(() -> "stored", this.tasks.database())
                .thenApplyAsync(step -> {
                    mainThreadSteps.add(Thread.currentThread());
                    return step + ", removed";
                }, this.tasks.mainThread())
                .thenApplyAsync(step -> step + ", released", this.tasks.database())
                .thenApplyAsync(step -> {
                    mainThreadSteps.add(Thread.currentThread());
                    return step + ", told";
                }, this.tasks.mainThread());

        Assertions.assertTrue(this.tasks.stop(PATIENCE));

        Assertions.assertEquals("stored, removed, released, told", chain.getNow("not finished"));
        Assertions.assertEquals(List.of(caller, caller), mainThreadSteps);
    }

    @Test
    void aTaskThatThrowsAtStopDoesNotKeepTheNextFromRunning() {
        this.scheduler.disable();
        this.tasks.mainThread().execute(() -> {
            throw new IllegalStateException("broken");
        });
        this.tasks.mainThread().execute(() -> this.ran.add("next"));

        Assertions.assertTrue(this.tasks.stop(PATIENCE));

        Assertions.assertEquals(List.of("next"), this.ran);
        Assertions.assertEquals(List.of(Level.SEVERE), loggedLevels());
    }

    @Test
    void aTaskRefusedATimerByTheStoppingServerIsNotReportedAsAFailure() {
        this.scheduler.disable();
        this.tasks.mainThread().execute(() -> {
            throw new IllegalPluginAccessException("Plugin attempted to register task while disabled");
        });
        this.tasks.mainThread().execute(() -> this.ran.add("next"));

        Assertions.assertTrue(this.tasks.stop(PATIENCE));

        Assertions.assertEquals(List.of("next"), this.ran);
        Assertions.assertEquals(List.of(Level.FINE), loggedLevels());
    }

    @Test
    void workThatOutlastsThePatienceIsAbandonedAndReported() {
        this.tasks.database().execute(() -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });

        Assertions.assertFalse(this.tasks.stop(Duration.ofMillis(50)));
    }

    @Test
    void workIsStillAcceptedBetweenFinishAndStop() {
        this.scheduler.disable();
        Assertions.assertTrue(this.tasks.finish(PATIENCE));

        this.tasks.database().execute(() -> this.tasks.mainThread().execute(() -> this.ran.add("after finish")));
        Assertions.assertTrue(this.tasks.stop(PATIENCE));

        Assertions.assertEquals(List.of("after finish"), this.ran);
    }

    @Test
    void nothingIsAcceptedOnceStopped() {
        this.tasks.stop(PATIENCE);

        Assertions.assertThrows(RejectedExecutionException.class, () -> this.tasks.mainThread().execute(() -> { }));
        Assertions.assertThrows(RejectedExecutionException.class, () -> this.tasks.database().execute(() -> { }));
        Assertions.assertThrows(RejectedExecutionException.class,
                () -> this.tasks.later(Duration.ofSeconds(1), () -> { }));
    }
}
