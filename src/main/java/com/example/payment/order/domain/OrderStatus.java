package com.example.payment.order.domain;

public enum OrderStatus {
  PENDING,
  PAYMENT_PROCESSING,
  PAID,
  PAYMENT_FAILED,
  REFUNDED,
  CANCELLED
}
