package com.example.payment.shared.outbox.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class OutboxEventEntityTest {

  @Test
  void rescheduleReleasesLeaseAndIncrementsRetryCount() {
    OutboxEventEntity event =
        OutboxEventEntity.pending("Payment", 1L, "payment.requested.v1", "topic", "1", "{}");
    LocalDateTime availableAt = LocalDateTime.now().plusMinutes(1);

    event.claim("publisher-1", LocalDateTime.now().plusSeconds(30));
    event.reschedule(availableAt, "TimeoutException: broker unavailable");

    assertEquals(OutboxEventStatus.PENDING, event.getStatus());
    assertEquals(1, event.getRetryCount());
    assertEquals(availableAt, event.getAvailableAt());
    assertNull(event.getLeaseOwner());
    assertNull(event.getLeaseExpiresAt());
  }

  @Test
  void markPublishedReleasesLeaseAndClearsLastError() {
    OutboxEventEntity event =
        OutboxEventEntity.pending("Payment", 1L, "payment.requested.v1", "topic", "1", "{}");
    LocalDateTime publishedAt = LocalDateTime.now();

    event.claim("publisher-1", publishedAt.plusSeconds(30));
    event.markPublished(publishedAt);

    assertEquals(OutboxEventStatus.PUBLISHED, event.getStatus());
    assertEquals(publishedAt, event.getPublishedAt());
    assertNull(event.getLeaseOwner());
    assertNull(event.getLeaseExpiresAt());
    assertNull(event.getLastError());
  }
}
