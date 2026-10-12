package com.example.payment.order.application;

import com.example.payment.order.domain.OrderEntity;
import java.util.List;
import java.util.Optional;

public interface OrderRepository {
  OrderEntity save(OrderEntity orderEntity);

  Optional<OrderEntity> findById(Long id);

  List<OrderEntity> findAll();
}
