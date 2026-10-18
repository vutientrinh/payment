package com.example.payment.payment.api.request;

import com.example.payment.payment.domain.PaymentMethod;
import jakarta.validation.constraints.NotNull;

public record CreatePaymentRequest(@NotNull Long orderId, @NotNull PaymentMethod method) {}
