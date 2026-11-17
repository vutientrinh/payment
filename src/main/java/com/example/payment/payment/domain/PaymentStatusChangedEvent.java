package com.example.payment.payment.domain;

public record PaymentStatusChangedEvent(
    Long paymentId,
    Long orderId,
    PaymentStatus previousStatus,
    PaymentStatus nextStatus,
    String providerReference,
    String failureReason) {}
