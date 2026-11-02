package com.example.payment.shared.messaging;

import java.util.Objects;
import java.util.UUID;

/**
 * Versioned wire contract for domain events published through the transactional outbox.
 *
 * <p>The envelope metadata is immutable once persisted. Incompatible payload changes require a new
 * event type version.
 */
public record EventEnvelope<T>(
    UUID eventId,
    String eventType,
    Long aggregateId,
    String occurredAt,
    String traceId,
    T payload) {

  public EventEnvelope {
    Objects.requireNonNull(eventId, "eventId must not be null");
    Objects.requireNonNull(eventType, "eventType must not be null");
    Objects.requireNonNull(aggregateId, "aggregateId must not be null");
    Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    Objects.requireNonNull(payload, "payload must not be null");
  }
}
