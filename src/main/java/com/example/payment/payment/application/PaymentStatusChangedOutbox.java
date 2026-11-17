package com.example.payment.payment.application;

import com.example.payment.payment.domain.PaymentEntity;
import com.example.payment.payment.domain.PaymentStatus;
import com.example.payment.payment.domain.PaymentStatusChangedEvent;
import com.example.payment.shared.messaging.EventEnvelope;
import com.example.payment.shared.outbox.application.OutboxEventRepository;
import com.example.payment.shared.outbox.domain.OutboxEventEntity;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
public class PaymentStatusChangedOutbox {
  static final String EVENT_TYPE = "payment.status-changed.v1";
  static final String TOPIC = "payment.status-changed.v1";

  private final OutboxEventRepository outboxEventRepository;
  private final JsonMapper jsonMapper;

  public void append(PaymentEntity payment, PaymentStatus previousStatus) {
    UUID eventId = UUID.randomUUID();
    String payload =
        jsonMapper.writeValueAsString(
            new EventEnvelope<>(
                eventId,
                EVENT_TYPE,
                payment.getId(),
                Instant.now().toString(),
                MDC.get("traceId"),
                new PaymentStatusChangedEvent(
                    payment.getId(),
                    payment.getOrder().getId(),
                    previousStatus,
                    payment.getPaymentStatus(),
                    payment.getProviderReference(),
                    payment.getFailureReason())));

    outboxEventRepository.save(
        OutboxEventEntity.pending(
            eventId,
            "Payment",
            payment.getId(),
            EVENT_TYPE,
            TOPIC,
            payment.getId().toString(),
            payload));
  }
}
