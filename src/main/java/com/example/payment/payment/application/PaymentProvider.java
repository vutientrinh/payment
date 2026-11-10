package com.example.payment.payment.application;

import com.example.payment.payment.domain.PaymentMethod;
import java.math.BigDecimal;

public interface PaymentProvider {
  PaymentProviderResult charge(
      Long id, BigDecimal amount, PaymentMethod method, String idempotencyKey);
}
