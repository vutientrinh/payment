package com.example.payment.payment.application;

import com.example.payment.payment.domain.PaymentEntity;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository {
  Optional<PaymentEntity> findById(Long id);

  List<PaymentEntity> findAll();

  PaymentEntity save(PaymentEntity paymentEntity);

  boolean existsByOrderId(Long orderId);
}
