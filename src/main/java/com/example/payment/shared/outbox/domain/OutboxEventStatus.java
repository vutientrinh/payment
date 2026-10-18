package com.example.payment.shared.outbox.domain;

public enum OutboxEventStatus {
  PENDING,
  IN_PROGRESS,
  PUBLISHED,
  FAILED
}
