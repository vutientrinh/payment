package com.example.payment.payment.domain;

public enum PaymentStatus {
  PENDING,
  PROCESSING,
  RECONCILIATION_REQUIRED,
  COMPLETED,
  FAILED,
  REFUNDED;

  public boolean canTransitionTo(PaymentStatus to) {
    return switch (this) {
      case PENDING -> to == PROCESSING || to == FAILED;
      case PROCESSING -> to == COMPLETED || to == FAILED || to == RECONCILIATION_REQUIRED;
      case RECONCILIATION_REQUIRED -> to == COMPLETED || to == FAILED;
      case COMPLETED -> to == REFUNDED;
      case FAILED, REFUNDED -> false;
    };
  }
}
