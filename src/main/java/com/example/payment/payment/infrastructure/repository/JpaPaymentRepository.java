package com.example.payment.payment.infrastructure.repository;

import com.example.payment.payment.application.PaymentRepository;
import com.example.payment.payment.domain.PaymentEntity;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JpaPaymentRepository implements PaymentRepository {
  private final JpaDataPaymentRepository jpaDataPaymentRepository;

  @Override
  public Optional<PaymentEntity> findById(Long id) {
    return jpaDataPaymentRepository.findById(id);
  }

  @Override
  public List<PaymentEntity> findAll() {
    return jpaDataPaymentRepository.findAll();
  }

  @Override
  public PaymentEntity save(PaymentEntity paymentEntity) {
    return jpaDataPaymentRepository.save(paymentEntity);
  }
}
