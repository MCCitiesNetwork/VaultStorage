package net.democracycraft.vault.internal.notification;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Keeps offline notices until the inbox provider accepts them. Never reads legacy mail. */
public final class NotificationOutbox {
    public record Notice(UUID id, UUID recipient, UUID sender, String senderName, String message, java.time.Instant createdAt) {
        public Notice {
            Objects.requireNonNull(id); Objects.requireNonNull(recipient); Objects.requireNonNull(sender);
            Objects.requireNonNull(senderName); Objects.requireNonNull(message); Objects.requireNonNull(createdAt);
        }
    }
    @FunctionalInterface public interface Delivery { boolean deliver(Notice notice) throws Exception; }
    private final Path directory;
    private final Delivery delivery;
    private final Logger logger;

    public NotificationOutbox(Path directory, Delivery delivery, Logger logger) {
        this.directory = Objects.requireNonNull(directory);
        this.delivery = Objects.requireNonNull(delivery);
        this.logger = Objects.requireNonNull(logger);
    }

    /** Returns true when accepted by the inbox; false means durably queued for retry. */
    public synchronized boolean submit(UUID recipient, UUID sender, String senderName, String message) throws IOException {
        Notice notice = new Notice(UUID.randomUUID(), recipient, sender, senderName, message, java.time.Instant.now());
        Files.createDirectories(directory);
        Properties values = new Properties();
        values.setProperty("id", notice.id().toString());
        values.setProperty("recipient", recipient.toString());
        values.setProperty("sender", sender.toString());
        values.setProperty("senderName", senderName);
        values.setProperty("message", message);
        values.setProperty("createdAt", notice.createdAt().toString());
        Path temporary = Files.createTempFile(directory, "notice-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) { values.store(output, "Pending offline notification"); }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, file(notice.id()), StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
        return attempt(notice);
    }

    public synchronized void flush() {
        if (!Files.isDirectory(directory)) return;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.properties")) {
            for (Path file : files) {
                try {
                    Properties values = new Properties();
                    try (InputStream input = Files.newInputStream(file)) { values.load(input); }
                    Notice notice = new Notice(UUID.fromString(values.getProperty("id")),
                            UUID.fromString(values.getProperty("recipient")), UUID.fromString(values.getProperty("sender")),
                            values.getProperty("senderName"), values.getProperty("message"),
                            java.time.Instant.parse(values.getProperty("createdAt")));
                    if (!file.getFileName().toString().equals(notice.id() + ".properties")) {
                        throw new IOException("Notification filename does not match its id");
                    }
                    attempt(notice);
                } catch (Exception exception) {
                    logger.log(Level.WARNING, "Pending notification retained: " + file.getFileName(), exception);
                }
            }
        } catch (IOException exception) { logger.log(Level.WARNING, "Cannot read pending notifications", exception); }
    }

    private boolean attempt(Notice notice) {
        try {
            if (!delivery.deliver(notice)) return false;
            Files.delete(file(notice.id()));
            return true;
        } catch (Exception | LinkageError exception) {
            logger.log(Level.WARNING, "Notification " + notice.id() + " retained for retry", exception);
            return false;
        }
    }
    private Path file(UUID id) { return directory.resolve(id + ".properties"); }
}
