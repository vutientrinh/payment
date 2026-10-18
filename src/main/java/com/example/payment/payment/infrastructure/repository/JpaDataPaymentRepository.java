package com.example.payment.payment.infrastructure.repository;

import com.example.payment.payment.domain.PaymentEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface JpaDataPaymentRepository extends JpaRepository<PaymentEntity, Long> {
  @Query("select payment from PaymentEntity payment join fetch payment.order")
  List<PaymentEntity> findAllWithOrder();

  @Query(
      "select payment from PaymentEntity payment join fetch payment.order where payment.id = :id")
  Optional<PaymentEntity> findByIdWithOrder(@Param("id") Long id);

  boolean existsByOrderId(Long orderId);
}
