package com.example.payment.payment.application.event;

import com.example.payment.payment.domain.PaymentMethod;
import java.math.BigDecimal;

public record PaymentRequestedEvent(
    Long paymentId, Long orderId, BigDecimal amount, PaymentMethod paymentMethod) {}
