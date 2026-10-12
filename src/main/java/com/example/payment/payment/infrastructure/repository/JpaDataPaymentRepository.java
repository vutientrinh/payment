package com.example.payment.payment.infrastructure.repository;

import com.example.payment.payment.domain.PaymentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface JpaDataPaymentRepository extends JpaRepository<PaymentEntity, Long> {}
