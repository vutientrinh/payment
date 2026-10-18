package com.example.payment.payment.api.response;

import com.example.payment.payment.domain.PaymentMethod;
import com.example.payment.payment.domain.PaymentStatus;

public record PaymentResponse(Long id, PaymentStatus status, PaymentMethod method, Long orderId) {}
