package com.example.payment.shared.outbox.domain;

import com.example.payment.shared.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
    name = "outbox_events",
    uniqueConstraints =
        @UniqueConstraint(name = "uk_outbox_events_event_id", columnNames = "event_id"))
@Getter
public class OutboxEventEntity extends BaseEntity {

  @Column(name = "event_id", nullable = false, updatable = false)
  private UUID eventId;

  @Column(name = "aggregate_type", nullable = false, length = 64)
  private String aggregateType;

  @Column(name = "aggregate_id", nullable = false)
  private Long aggregateId;

  @Column(name = "event_type", nullable = false, length = 128)
  private String eventType;

  @Column(nullable = false, length = 255)
  private String topic;

  @Column(name = "event_key", nullable = false, length = 255)
  private String eventKey;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private String payload;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private OutboxEventStatus status;

  @Column(name = "available_at", nullable = false)
  private LocalDateTime availableAt;

  @Column(name = "published_at")
  private LocalDateTime publishedAt;

  @Column(name = "lease_owner", length = 128)
  private String leaseOwner;

  @Column(name = "lease_expires_at")
  private LocalDateTime leaseExpiresAt;

  @Column(name = "retry_count", nullable = false)
  private int retryCount;

  @Column(name = "last_error", length = 1000)
  private String lastError;

  public static OutboxEventEntity pending(
      String aggregateType,
      Long aggregateId,
      String eventType,
      String topic,
      String eventKey,
      String payload) {
    OutboxEventEntity event = new OutboxEventEntity();
    event.eventId = UUID.randomUUID();
    event.aggregateType = aggregateType;
    event.aggregateId = aggregateId;
    event.eventType = eventType;
    event.topic = topic;
    event.eventKey = eventKey;
    event.payload = payload;
    event.status = OutboxEventStatus.PENDING;
    event.availableAt = LocalDateTime.now();
    return event;
  }

  public void claim(String owner, LocalDateTime expiresAt) {
    status = OutboxEventStatus.IN_PROGRESS;
    leaseOwner = owner;
    leaseExpiresAt = expiresAt;
  }

  public void markPublished(LocalDateTime publishedAt) {
    status = OutboxEventStatus.PUBLISHED;
    this.publishedAt = publishedAt;
    leaseOwner = null;
    leaseExpiresAt = null;
    lastError = null;
  }

  public void reschedule(LocalDateTime availableAt, String error) {
    status = OutboxEventStatus.PENDING;
    this.availableAt = availableAt;
    retryCount++;
    leaseOwner = null;
    leaseExpiresAt = null;
    lastError = error;
  }

  public void markFailed(String error) {
    status = OutboxEventStatus.FAILED;
    retryCount++;
    leaseOwner = null;
    leaseExpiresAt = null;
    lastError = error;
  }
}
