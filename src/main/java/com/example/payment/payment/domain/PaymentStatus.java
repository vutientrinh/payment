package com.example.payment.payment.domain;

public enum PaymentStatus {
  PENDING,
  PROCESSING,
  COMPLETED,
  FAILED,
  REFUNDED;

  public boolean canTransitionTo(PaymentStatus to) {
    return switch (this) {
      case PENDING -> to == PROCESSING || to == FAILED;
      case PROCESSING -> to == COMPLETED || to == FAILED;
      case COMPLETED -> to == REFUNDED;
      case FAILED, REFUNDED -> false;
    };
  }
}
