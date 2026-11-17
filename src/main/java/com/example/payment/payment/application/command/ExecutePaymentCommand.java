package com.example.payment.payment.application.command;

import java.util.UUID;

public record ExecutePaymentCommand(UUID eventId, Long paymentId) {}
