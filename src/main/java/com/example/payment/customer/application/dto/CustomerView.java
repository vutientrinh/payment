package com.example.payment.customer.application.dto;

import com.example.payment.customer.domain.UserEntity;

public record CustomerView(
    Long id, String firstName, String lastName, String email, String phoneNumber) {

  public static CustomerView from(UserEntity customer) {
    return new CustomerView(
        customer.getId(),
        customer.getFirstName(),
        customer.getLastName(),
        customer.getEmail(),
        customer.getPhoneNumber());
  }
}
