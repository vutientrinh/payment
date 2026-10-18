package com.example.payment.shared.outbox.infrastructure.messaging;

import com.example.payment.shared.outbox.application.OutboxEventRepository;
import com.example.payment.shared.outbox.domain.OutboxEventEntity;
import com.example.payment.shared.outbox.infrastructure.config.OutboxPublisherProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "payment.outbox.publisher.enabled", havingValue = "true")
public class OutboxEventPublisher {
  private static final int MAX_ERROR_LENGTH = 1000;

  private final OutboxEventRepository outboxEventRepository;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final OutboxPublisherProperties properties;
  private final String leaseOwner = UUID.randomUUID().toString();

  public OutboxEventPublisher(
      OutboxEventRepository outboxEventRepository,
      KafkaTemplate<String, String> kafkaTemplate,
      OutboxPublisherProperties properties) {
    this.outboxEventRepository = outboxEventRepository;
    this.kafkaTemplate = kafkaTemplate;
    this.properties = properties;
  }

  @Scheduled(fixedDelayString = "${payment.outbox.publisher.fixed-delay:1s}")
  public void publishPendingEvents() {
    for (int batchNumber = 0; batchNumber < properties.getMaxBatchesPerRun(); batchNumber++) {
      LocalDateTime now = LocalDateTime.now();
      List<OutboxEventEntity> events =
          outboxEventRepository.claimPendingBatch(
              leaseOwner, now, now.plus(properties.getLeaseDuration()), properties.getBatchSize());
      if (events.isEmpty()) {
        return;
      }

      events.forEach(this::publish);
    }
  }

  private void publish(OutboxEventEntity event) {
    try {
      kafkaTemplate
          .send(toProducerRecord(event))
          .get(properties.getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);

      boolean markedPublished =
          outboxEventRepository.markPublished(event.getId(), leaseOwner, LocalDateTime.now());
      if (!markedPublished) {
        log.warn(
            "Outbox event {} was acknowledged by Kafka but its publish lease was no longer owned",
            event.getEventId());
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      recordFailure(event, exception);
    } catch (ExecutionException | TimeoutException | RuntimeException exception) {
      recordFailure(event, exception);
    }
  }

  private void recordFailure(OutboxEventEntity event, Exception exception) {
    Duration retryDelay = calculateRetryDelay(event.getRetryCount() + 1);
    boolean recorded =
        outboxEventRepository.recordPublishFailure(
            event.getId(),
            leaseOwner,
            LocalDateTime.now().plus(retryDelay),
            failureMessage(exception),
            properties.getMaxAttempts());
    if (!recorded) {
      log.warn(
          "Outbox event {} failed but its publish lease was no longer owned", event.getEventId());
      return;
    }

    log.warn(
        "Kafka publish failed for outbox event {}. It will be retried or marked failed according to policy",
        event.getEventId(),
        exception);
  }

  private Duration calculateRetryDelay(int attempt) {
    long initialDelayMillis = properties.getInitialBackoff().toMillis();
    long maxDelayMillis = properties.getMaxBackoff().toMillis();
    long multiplier = 1L << Math.min(Math.max(attempt - 1, 0), 30);
    long exponentialDelay =
        initialDelayMillis > maxDelayMillis / multiplier
            ? maxDelayMillis
            : initialDelayMillis * multiplier;
    long jitteredDelay =
        ThreadLocalRandom.current()
            .nextLong(Math.max(1, exponentialDelay / 2), Math.max(2, exponentialDelay + 1));
    return Duration.ofMillis(Math.min(jitteredDelay, maxDelayMillis));
  }

  private ProducerRecord<String, String> toProducerRecord(OutboxEventEntity event) {
    ProducerRecord<String, String> record =
        new ProducerRecord<>(event.getTopic(), event.getEventKey(), event.getPayload());
    record.headers().add("eventId", event.getEventId().toString().getBytes(StandardCharsets.UTF_8));
    record.headers().add("eventType", event.getEventType().getBytes(StandardCharsets.UTF_8));
    return record;
  }

  private String failureMessage(Exception exception) {
    Throwable cause =
        exception instanceof ExecutionException && exception.getCause() != null
            ? exception.getCause()
            : exception;
    String message = cause.getMessage();
    String value = cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    return value.substring(0, Math.min(value.length(), MAX_ERROR_LENGTH));
  }
}
