package com.example.payment.payment.infrastructure.messaging;

import com.example.payment.payment.application.PaymentExecutionService;
import com.example.payment.payment.application.command.ExecutePaymentCommand;
import com.example.payment.payment.application.event.PaymentRequestedEvent;
import com.example.payment.shared.messaging.EventEnvelope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.executor.enabled", havingValue = "true")
public class PaymentRequestedConsumer {
  private static final TypeReference<EventEnvelope<PaymentRequestedEvent>> ENVELOPE_TYPE =
      new TypeReference<>() {};
  private final JsonMapper jsonMapper;
  private final PaymentExecutionService paymentExecutionService;

  @KafkaListener(
      id = "payment-executor",
      topics = "payment.requested.v1",
      groupId = "${payment.executor.consumer-group}")
  public void onPaymentRequested(
      ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
    EventEnvelope<PaymentRequestedEvent> eventEnvelope =
        jsonMapper.readValue(record.value(), ENVELOPE_TYPE);
    try (MDC.MDCCloseable trace = MDC.putCloseable("traceId", eventEnvelope.traceId())) {
      log.info(
          "Received {} eventId = {} paymentId = {} partition = {} offset = {}",
          eventEnvelope.eventType(),
          eventEnvelope.eventId(),
          eventEnvelope.payload().paymentId(),
          record.partition(),
          record.offset());
      paymentExecutionService.execute(
          new ExecutePaymentCommand(eventEnvelope.eventId(), eventEnvelope.payload().paymentId()));
    }
    acknowledgment.acknowledge();
  }
}
