package com.example.payment.shared.inbox.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "consumed_events",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_consumed_events_consumer_event",
            columnNames = {"consumer_name", "event_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConsumedEventEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "consumer_name", nullable = false, length = 128)
  private String consumerName;

  @Column(name = "event_id", nullable = false)
  private UUID eventId;

  @Column(name = "consumed_at", nullable = false)
  private LocalDateTime consumedAt;
}
