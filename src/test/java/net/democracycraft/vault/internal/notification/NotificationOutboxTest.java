package net.democracycraft.vault.internal.notification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class NotificationOutboxTest {
 @TempDir Path directory;
 private final UUID recipient = UUID.randomUUID(), sender = UUID.randomUUID();
 private NotificationOutbox outbox(NotificationOutbox.Delivery delivery) {
  return new NotificationOutbox(directory, delivery, Logger.getLogger("outbox-test"));
 }
 private long pending() throws Exception { try (var files = Files.list(directory)) { return files.filter(p -> p.toString().endsWith(".properties")).count(); } }
 @Test void unavailableProviderRetainsCompleteNoticeAcrossRestart() throws Exception {
  assertFalse(outbox(n -> false).submit(recipient, sender, "Console", "§aJob changed\n雪"));
  assertEquals(1, pending());
  List<NotificationOutbox.Notice> accepted = new ArrayList<>();
  outbox(n -> { accepted.add(n); return true; }).flush();
  assertEquals(1, accepted.size());
  var n = accepted.getFirst();
  assertEquals(recipient, n.recipient()); assertEquals(sender, n.sender());
  assertEquals("Console", n.senderName()); assertEquals("§aJob changed\n雪", n.message());
  assertNotNull(n.createdAt()); assertEquals(0, pending());
 }
 @Test void failedDeliveryRetainsStableKeyAndTimestampForRetry() throws Exception {
  List<NotificationOutbox.Notice> attempts = new ArrayList<>();
  var queue = outbox(n -> { attempts.add(n); throw new IllegalStateException("provider down"); });
  assertFalse(queue.submit(recipient, sender, "Actor", "Notice"));
  assertEquals(1, pending());
  outbox(n -> { attempts.add(n); return true; }).flush();
  assertEquals(attempts.get(0), attempts.get(1)); assertEquals(0, pending());
 }
 @Test void absentOptionalApiKeepsNotice() throws Exception {
  assertFalse(outbox(n -> { throw new NoClassDefFoundError("optional API"); }).submit(recipient, sender, "Actor", "Notice"));
  assertEquals(1, pending());
 }
 @Test void deliveryOccursOnlyAfterDurableWrite() throws Exception {
  assertTrue(outbox(n -> { assertTrue(Files.exists(directory.resolve(n.id()+".properties"))); return true; })
    .submit(recipient, sender, "Actor", "Notice"));
  assertEquals(0, pending());
 }
 @Test void failedPersistenceNeverClaimsDelivery() throws Exception {
  Path occupied = directory.resolve("not-a-directory"); Files.writeString(occupied, "preserve");
  AtomicInteger calls = new AtomicInteger();
  var queue = new NotificationOutbox(occupied, n -> { calls.incrementAndGet(); return true; }, Logger.getLogger("test"));
  assertThrows(java.io.IOException.class, () -> queue.submit(recipient, sender, "Actor", "Notice"));
  assertEquals(0, calls.get()); assertEquals("preserve", Files.readString(occupied));
 }
 @Test void corruptPendingFileIsRetainedAndDoesNotBlockOtherNotices() throws Exception {
  Path corrupt = directory.resolve(UUID.randomUUID()+".properties"); Files.writeString(corrupt, "invalid=yes");
  assertFalse(outbox(n -> false).submit(recipient, sender, "Actor", "Notice"));
  AtomicInteger calls = new AtomicInteger(); outbox(n -> { calls.incrementAndGet(); return true; }).flush();
  assertEquals(1, calls.get()); assertTrue(Files.exists(corrupt)); assertEquals(1, pending());
 }
}
