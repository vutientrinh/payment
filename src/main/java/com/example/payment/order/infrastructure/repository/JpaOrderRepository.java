package com.example.payment.order.infrastructure.repository;

import com.example.payment.order.application.OrderRepository;
import com.example.payment.order.domain.OrderEntity;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JpaOrderRepository implements OrderRepository {
  private final JpaDataOrderRepository jpaDataOrderRepository;

  @Override
  public OrderEntity save(OrderEntity orderEntity) {
    return jpaDataOrderRepository.save(orderEntity);
  }

  @Override
  public Optional<OrderEntity> findById(Long id) {
    return jpaDataOrderRepository.findById(id);
  }

  @Override
  public List<OrderEntity> findAll() {
    return jpaDataOrderRepository.findAll();
  }
}
