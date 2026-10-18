package com.example.payment.payment.api.request;

import com.example.payment.payment.domain.PaymentStatus;
import jakarta.validation.constraints.NotNull;

public record UpdatePaymentStatusRequest(@NotNull PaymentStatus status) {}
