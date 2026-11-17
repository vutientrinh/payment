package com.example.payment.payment.application;

import com.example.payment.payment.application.command.ExecutePaymentCommand;

public interface PaymentExecutionService {
  void execute(ExecutePaymentCommand command);
}
