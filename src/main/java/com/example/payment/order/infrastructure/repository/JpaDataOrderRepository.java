package com.example.payment.order.infrastructure.repository;

import com.example.payment.order.domain.OrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface JpaDataOrderRepository extends JpaRepository<OrderEntity, Long> {}
