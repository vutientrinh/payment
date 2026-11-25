package com.example.payment.payment.application;

import com.example.payment.payment.domain.PaymentMethod;
import java.math.BigDecimal;
import java.util.Optional;

public interface PaymentProvider {
  PaymentProviderResult charge(
      Long paymentId, BigDecimal amount, PaymentMethod method, String idempotencyKey);

  Optional<PaymentProviderResult> findByIdempotencyKey(String idempotencyKey);
}
