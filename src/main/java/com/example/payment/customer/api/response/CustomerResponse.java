package com.example.payment.customer.api.response;

public record CustomerResponse(
    Long id, String firstName, String lastName, String email, String phoneNumber) {}
