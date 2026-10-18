package com.example.payment.shared.application;

public class ConflictException extends RuntimeException {
  public ConflictException(String message) {
    super(message);
  }
}
