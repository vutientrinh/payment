package com.example.payment.shared.application;

public class ResourceNotFoundException extends RuntimeException {
  public ResourceNotFoundException(String resource, Long id) {
    super(resource + " not found: " + id);
  }
}
