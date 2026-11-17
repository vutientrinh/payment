package com.example.payment.payment.application;

import com.example.payment.payment.application.command.ExecutePaymentCommand;
import com.example.payment.payment.domain.PaymentEntity;
import com.example.payment.payment.domain.PaymentMethod;
import com.example.payment.payment.domain.PaymentStatus;
import com.example.payment.shared.application.ResourceNotFoundException;
import com.example.payment.shared.inbox.application.ConsumedEventRepository;
import com.example.payment.shared.inbox.domain.ConsumedEventEntity;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentExecutionServiceImpl implements PaymentExecutionService {
  static final String CONSUMER_NAME = "payment-executor";
  private final PaymentRepository paymentRepository;
  private final ConsumedEventRepository consumedEventRepository;
  private final PaymentProvider paymentProvider;
  private final PaymentStatusChangedOutbox paymentStatusChangedOutbox;
  private final TransactionTemplate transactionTemplate;

  private record Charge(Long paymentId, BigDecimal amount, PaymentMethod paymentMethod) {}

  @Override
  public void execute(ExecutePaymentCommand command) {
    Charge charge = transactionTemplate.execute(status -> startProcessing(command));
    if (charge == null) {
      return;
    }
    PaymentProviderResult result =
        paymentProvider.charge(
            charge.paymentId(),
            charge.amount(),
            charge.paymentMethod(),
            idempotencyKey(charge.paymentId()));
    transactionTemplate.executeWithoutResult(status -> finish(command, result));
  }

  private Charge startProcessing(ExecutePaymentCommand command) {
    if (consumedEventRepository.exists(CONSUMER_NAME, command.eventId())) {
      log.info("Event {} already consumed -- skipping", command.eventId());
      return null;
    }
    PaymentEntity payment = loadPayment(command.paymentId());
    switch (payment.getPaymentStatus()) {
      case PENDING -> {
        payment.transitionTo(PaymentStatus.PROCESSING);
        paymentStatusChangedOutbox.append(payment, PaymentStatus.PENDING);
      }
      case PROCESSING -> {
        log.warn("Payment {} already PROCESSING - resuming", command.paymentId());
      }
      default -> {
        log.info(
            "Payment {} is {} -- nothing to execute",
            command.paymentId(),
            payment.getPaymentStatus());
        consumedEventRepository.save(ConsumedEventEntity.of(CONSUMER_NAME, command.eventId()));
        return null;
      }
    }

    return new Charge(
        payment.getId(), payment.getOrder().getOrderAmount(), payment.getPaymentMethod());
  }

  private void finish(ExecutePaymentCommand command, PaymentProviderResult result) {
    if (consumedEventRepository.exists(CONSUMER_NAME, command.eventId())) {
      return;
    }
    PaymentEntity payment = loadPayment(command.paymentId());
    if (payment.getPaymentStatus().equals(PaymentStatus.PROCESSING)) {
      switch (result.outcome()) {
        case SUCCEEDED -> payment.complete(result.providerReference());
        case DECLINED -> payment.fail(result.providerReference(), result.failureReason());
      }
      paymentStatusChangedOutbox.append(payment, PaymentStatus.PROCESSING);
      log.info("Payment {} -> {}", payment.getId(), payment.getPaymentStatus());
    } else {
      log.warn(
          "Payment {} changed to {} while charging -- result not applied",
          payment.getId(),
          payment.getPaymentStatus());
    }
    consumedEventRepository.save(ConsumedEventEntity.of(CONSUMER_NAME, command.eventId()));
  }

  private PaymentEntity loadPayment(Long paymentId) {
    return paymentRepository
        .findById(paymentId)
        .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
  }

  static String idempotencyKey(Long paymentId) {
    return "payment-" + paymentId;
  }
}
