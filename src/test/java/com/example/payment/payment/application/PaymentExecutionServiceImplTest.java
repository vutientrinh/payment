package com.example.payment.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.example.payment.order.domain.OrderEntity;
import com.example.payment.order.domain.OrderStatus;
import com.example.payment.payment.application.command.ExecutePaymentCommand;
import com.example.payment.payment.domain.PaymentEntity;
import com.example.payment.payment.domain.PaymentMethod;
import com.example.payment.payment.domain.PaymentStatus;
import com.example.payment.shared.inbox.application.ConsumedEventRepository;
import com.example.payment.shared.inbox.domain.ConsumedEventEntity;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class PaymentExecutionServiceImplTest {
  private static final String CONSUMER = PaymentExecutionServiceImpl.CONSUMER_NAME;
  private static final BigDecimal AMOUNT = new BigDecimal("120000.00");

  @Mock private PaymentRepository paymentRepository;
  @Mock private ConsumedEventRepository consumedEventRepository;
  @Mock private PaymentProvider paymentProvider;
  @Mock private PaymentStatusChangedOutbox paymentStatusChangedOutbox;
  @Mock private PlatformTransactionManager transactionManager;
  private PaymentExecutionServiceImpl paymentExecutionService;
  private final UUID eventId = UUID.randomUUID();
  private final ExecutePaymentCommand command = new ExecutePaymentCommand(eventId, 99L);

  @BeforeEach
  void setUp() {
    paymentExecutionService =
        new PaymentExecutionServiceImpl(
            paymentRepository,
            consumedEventRepository,
            paymentProvider,
            paymentStatusChangedOutbox,
            new TransactionTemplate(transactionManager));
  }

  @Test
  void successfulChargeCompletesPaymentAndOrder() {
    PaymentEntity payment = payment(PaymentStatus.PENDING);
    when(paymentRepository.findById(99L)).thenReturn(Optional.of(payment));
    when(paymentProvider.charge(99L, AMOUNT, PaymentMethod.EWALLET, "payment-99"))
        .thenReturn(PaymentProviderResult.succeeded("STUB-1"));
    paymentExecutionService.execute(command);
    assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(payment.getOrder().getOrderStatus()).isEqualTo(OrderStatus.PAID);
    assertThat(payment.getProviderReference()).isEqualTo("STUB-1");
    verify(paymentStatusChangedOutbox, times(2)).append(eq(payment), any());
    verify(consumedEventRepository).save(any(ConsumedEventEntity.class));
  }

  @Test
  void declinedChargeFailsPaymentAndOrder() {
    PaymentEntity payment = payment(PaymentStatus.PENDING);
    when(paymentRepository.findById(99L)).thenReturn(Optional.of(payment));
    when(paymentProvider.charge(any(), any(), any(), any()))
        .thenReturn(PaymentProviderResult.declined("STUB-2", "Amount exceeds stub limit"));
    paymentExecutionService.execute(command);
    assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.FAILED);
    assertThat(payment.getOrder().getOrderStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
    assertThat(payment.getFailureReason()).isEqualTo("Amount exceeds stub limit");
  }

  @Test
  void duplicatePaymentFailsPaymentAndOrder() {
    when(consumedEventRepository.exists(CONSUMER, eventId)).thenReturn(true);
    paymentExecutionService.execute(command);
    verifyNoInteractions(paymentProvider, paymentStatusChangedOutbox);
    verify(paymentRepository, never()).findById(any());
  }

  @Test
  void terminatedPaymentIsNeverCharged() {
    when(paymentRepository.findById(99L)).thenReturn(Optional.of(payment(PaymentStatus.COMPLETED)));
    paymentExecutionService.execute(command);
    verifyNoInteractions(paymentProvider, paymentStatusChangedOutbox);
    verify(consumedEventRepository).save(any(ConsumedEventEntity.class));
  }

  @Test
  void processingPaymentIsResumedWithSameIdempotencyKey() {
    PaymentEntity payment = payment(PaymentStatus.PROCESSING);
    when(paymentRepository.findById(99L)).thenReturn(Optional.of(payment));
    when(paymentProvider.charge(99L, AMOUNT, PaymentMethod.EWALLET, "payment-99"))
        .thenReturn(PaymentProviderResult.succeeded("STUB-1"));
    paymentExecutionService.execute(command);
    assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.COMPLETED);
    verify(paymentStatusChangedOutbox, times(1)).append(payment, PaymentStatus.PROCESSING);
  }

  private static PaymentEntity payment(PaymentStatus paymentStatus) {
    OrderEntity order = new OrderEntity();
    ReflectionTestUtils.setField(order, "id", 42L);
    order.setOrderStatus(OrderStatus.PAYMENT_PROCESSING);
    order.setOrderAmount(AMOUNT);
    PaymentEntity payment = new PaymentEntity();
    ReflectionTestUtils.setField(payment, "id", 99L);
    payment.setOrder(order);
    payment.setPaymentMethod(PaymentMethod.EWALLET);
    payment.setPaymentStatus(paymentStatus);
    return payment;
  }
}
