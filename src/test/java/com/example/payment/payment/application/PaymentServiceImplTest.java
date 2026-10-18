package com.example.payment.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.payment.order.application.OrderRepository;
import com.example.payment.order.domain.OrderEntity;
import com.example.payment.order.domain.OrderStatus;
import com.example.payment.payment.domain.PaymentEntity;
import com.example.payment.payment.domain.PaymentMethod;
import com.example.payment.shared.outbox.application.OutboxEventRepository;
import com.example.payment.shared.outbox.domain.OutboxEventEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PaymentServiceImplTest {

  @Mock private PaymentRepository paymentRepository;
  @Mock private OrderRepository orderRepository;
  @Mock private OutboxEventRepository outboxEventRepository;
  @Captor private ArgumentCaptor<OutboxEventEntity> outboxEventCaptor;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void createPaymentPersistsPendingOutboxEvent() throws Exception {
    OrderEntity order = new OrderEntity();
    ReflectionTestUtils.setField(order, "id", 42L);
    order.setOrderStatus(OrderStatus.PENDING);
    order.setOrderAmount(new BigDecimal("120000"));

    when(paymentRepository.existsByOrderId(42L)).thenReturn(false);
    when(orderRepository.findById(42L)).thenReturn(java.util.Optional.of(order));
    when(paymentRepository.save(any(PaymentEntity.class)))
        .thenAnswer(
            invocation -> {
              PaymentEntity payment = invocation.getArgument(0);
              ReflectionTestUtils.setField(payment, "id", 99L);
              return payment;
            });

    PaymentServiceImpl service =
        new PaymentServiceImpl(
            paymentRepository, orderRepository, outboxEventRepository, objectMapper);

    service.createPayment(42L, PaymentMethod.EWALLET);

    verify(outboxEventRepository).save(outboxEventCaptor.capture());
    OutboxEventEntity event = outboxEventCaptor.getValue();
    JsonNode payload = objectMapper.readTree(event.getPayload());

    assertNotNull(event.getEventId());
    assertEquals("Payment", event.getAggregateType());
    assertEquals(99L, event.getAggregateId());
    assertEquals("payment.requested.v1", event.getEventType());
    assertEquals("payment.requested.v1", event.getTopic());
    assertEquals("99", event.getEventKey());
    assertEquals(99L, payload.get("paymentId").asLong());
    assertEquals(42L, payload.get("orderId").asLong());
    assertEquals(120000, payload.get("amount").asInt());
    assertEquals("EWALLET", payload.get("paymentMethod").asText());
  }
}
