package com.example.payment.payment.application;

import com.example.payment.order.application.OrderRepository;
import com.example.payment.order.domain.OrderEntity;
import com.example.payment.order.domain.OrderStatus;
import com.example.payment.payment.application.dto.PaymentView;
import com.example.payment.payment.application.event.PaymentRequestedEvent;
import com.example.payment.payment.domain.PaymentEntity;
import com.example.payment.payment.domain.PaymentMethod;
import com.example.payment.payment.domain.PaymentStatus;
import com.example.payment.shared.application.ConflictException;
import com.example.payment.shared.application.ResourceNotFoundException;
import com.example.payment.shared.outbox.application.OutboxEventRepository;
import com.example.payment.shared.outbox.domain.OutboxEventEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {
  private static final String PAYMENT_REQUESTED_EVENT = "payment.requested.v1";
  private static final String PAYMENT_REQUESTED_TOPIC = "payment.requested.v1";

  private final PaymentRepository paymentRepository;
  private final OrderRepository orderRepository;
  private final OutboxEventRepository outboxEventRepository;
  private final ObjectMapper objectMapper;

  @Override
  @Transactional(readOnly = true)
  public List<PaymentView> getAllPayments() {
    return paymentRepository.findAll().stream().map(PaymentView::from).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public PaymentView getPaymentById(Long id) {
    return PaymentView.from(getPayment(id));
  }

  @Override
  public PaymentView createPayment(Long orderId, PaymentMethod paymentMethod) {
    if (paymentRepository.existsByOrderId(orderId)) {
      throw new ConflictException("A payment already exists for this order");
    }

    OrderEntity order =
        orderRepository
            .findById(orderId)
            .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
    if (order.getOrderStatus() != OrderStatus.PENDING) {
      throw new ConflictException("Payment can only be created for a pending order");
    }

    PaymentEntity payment = new PaymentEntity();
    payment.setOrder(order);
    payment.setPaymentMethod(paymentMethod);
    payment.setPaymentStatus(PaymentStatus.PENDING);
    order.setOrderStatus(OrderStatus.PAYMENT_PROCESSING);
    PaymentEntity savedPayment = paymentRepository.save(payment);
    outboxEventRepository.save(
        OutboxEventEntity.pending(
            "Payment",
            savedPayment.getId(),
            PAYMENT_REQUESTED_EVENT,
            PAYMENT_REQUESTED_TOPIC,
            savedPayment.getId().toString(),
            serializePaymentRequestedEvent(savedPayment)));
    return PaymentView.from(savedPayment);
  }

  @Override
  public PaymentView updatePaymentStatus(Long id, PaymentStatus status) {
    PaymentEntity payment = getPayment(id);
    if (!isTransitionAllowed(payment.getPaymentStatus(), status)) {
      throw new ConflictException(
          "Payment status cannot transition from " + payment.getPaymentStatus() + " to " + status);
    }

    payment.setPaymentStatus(status);
    payment.getOrder().setOrderStatus(toOrderStatus(status));
    return PaymentView.from(payment);
  }

  private PaymentEntity getPayment(Long id) {
    return paymentRepository
        .findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Payment", id));
  }

  private String serializePaymentRequestedEvent(PaymentEntity payment) {
    try {
      return objectMapper.writeValueAsString(
          new PaymentRequestedEvent(
              payment.getId(),
              payment.getOrder().getId(),
              payment.getOrder().getOrderAmount(),
              payment.getPaymentMethod()));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to serialize payment.requested.v1 event", exception);
    }
  }

  private boolean isTransitionAllowed(PaymentStatus current, PaymentStatus next) {
    return switch (current) {
      case PENDING -> next == PaymentStatus.PROCESSING || next == PaymentStatus.FAILED;
      case PROCESSING -> next == PaymentStatus.COMPLETED || next == PaymentStatus.FAILED;
      case COMPLETED -> next == PaymentStatus.REFUNDED;
      case FAILED, REFUNDED -> false;
    };
  }

  private OrderStatus toOrderStatus(PaymentStatus paymentStatus) {
    return switch (paymentStatus) {
      case PENDING, PROCESSING -> OrderStatus.PAYMENT_PROCESSING;
      case COMPLETED -> OrderStatus.PAID;
      case FAILED -> OrderStatus.PAYMENT_FAILED;
      case REFUNDED -> OrderStatus.REFUNDED;
    };
  }
}
