package com.example.payment.shared.outbox.infrastructure.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.payment.shared.outbox.application.OutboxEventRepository;
import com.example.payment.shared.outbox.domain.OutboxEventEntity;
import com.example.payment.shared.outbox.infrastructure.config.OutboxPublisherProperties;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OutboxEventPublisherTest {

  @Mock private OutboxEventRepository outboxEventRepository;
  @Mock private KafkaTemplate<String, String> kafkaTemplate;
  @Captor private ArgumentCaptor<ProducerRecord<String, String>> producerRecordCaptor;

  @Test
  void publishPendingEventsPublishesEventWithDeduplicationHeadersAndMarksItPublished()
      throws Exception {
    OutboxEventEntity event =
        OutboxEventEntity.pending("Payment", 1L, "payment.requested.v1", "topic", "1", "{}");
    ReflectionTestUtils.setField(event, "id", 100L);

    when(outboxEventRepository.claimPendingBatch(anyString(), any(), any(), eq(50)))
        .thenReturn(List.of(event), List.of());
    CompletableFuture<SendResult<String, String>> successfulSend =
        CompletableFuture.completedFuture(null);
    when(kafkaTemplate.send(org.mockito.ArgumentMatchers.<ProducerRecord<String, String>>any()))
        .thenReturn(successfulSend);
    when(outboxEventRepository.markPublished(eq(100L), anyString(), any())).thenReturn(true);

    new OutboxEventPublisher(outboxEventRepository, kafkaTemplate, new OutboxPublisherProperties())
        .publishPendingEvents();

    verify(kafkaTemplate).send(producerRecordCaptor.capture());
    verify(outboxEventRepository).markPublished(eq(100L), anyString(), any());

    ProducerRecord<String, String> record = producerRecordCaptor.getValue();
    assertEquals("topic", record.topic());
    assertEquals("1", record.key());
    assertEquals("{}", record.value());
    assertEquals(
        event.getEventId().toString(),
        new String(record.headers().lastHeader("eventId").value(), StandardCharsets.UTF_8));
    assertEquals(
        "payment.requested.v1",
        new String(record.headers().lastHeader("eventType").value(), StandardCharsets.UTF_8));
  }

  @Test
  void publishPendingEventsReschedulesEventWhenKafkaPublishFails() {
    OutboxEventEntity event =
        OutboxEventEntity.pending("Payment", 1L, "payment.requested.v1", "topic", "1", "{}");
    ReflectionTestUtils.setField(event, "id", 101L);

    when(outboxEventRepository.claimPendingBatch(anyString(), any(), any(), eq(50)))
        .thenReturn(List.of(event), List.of());
    CompletableFuture<SendResult<String, String>> failedSend =
        CompletableFuture.failedFuture(new RuntimeException("broker unavailable"));
    when(kafkaTemplate.send(org.mockito.ArgumentMatchers.<ProducerRecord<String, String>>any()))
        .thenReturn(failedSend);
    when(outboxEventRepository.recordPublishFailure(
            eq(101L), anyString(), any(LocalDateTime.class), anyString(), eq(10)))
        .thenReturn(true);

    new OutboxEventPublisher(outboxEventRepository, kafkaTemplate, new OutboxPublisherProperties())
        .publishPendingEvents();

    verify(outboxEventRepository)
        .recordPublishFailure(eq(101L), anyString(), any(LocalDateTime.class), anyString(), eq(10));
  }
}
