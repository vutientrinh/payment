package com.example.payment.payment.application.dto;

import com.example.payment.payment.domain.PaymentEntity;
import com.example.payment.payment.domain.PaymentMethod;
import com.example.payment.payment.domain.PaymentStatus;

public record PaymentView(Long id, PaymentStatus status, PaymentMethod method, Long orderId) {
  public static PaymentView from(PaymentEntity payment) {
    return new PaymentView(
        payment.getId(),
        payment.getPaymentStatus(),
        payment.getPaymentMethod(),
        payment.getOrder().getId());
  }
}
