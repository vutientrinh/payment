package com.example.payment.payment.application;

import com.example.payment.payment.application.dto.PaymentView;
import com.example.payment.payment.domain.PaymentMethod;
import com.example.payment.payment.domain.PaymentStatus;
import java.util.List;

public interface PaymentService {
  List<PaymentView> getAllPayments();

  PaymentView getPaymentById(Long id);

  PaymentView createPayment(Long orderId, PaymentMethod paymentMethod);

  PaymentView updatePaymentStatus(Long id, PaymentStatus status);
}
