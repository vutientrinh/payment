package com.example.payment.order.infrastructure.repository;

import com.example.payment.order.domain.OrderEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface JpaDataOrderRepository extends JpaRepository<OrderEntity, Long> {

  @Query(
      "select distinct o from OrderEntity o "
          + "join fetch o.user "
          + "left join fetch o.orderItems "
          + "where o.isActive = true")
  List<OrderEntity> findAllActiveWithDetails();

  @Query(
      "select distinct o from OrderEntity o "
          + "join fetch o.user "
          + "left join fetch o.orderItems "
          + "where o.id = :id and o.isActive = true")
  Optional<OrderEntity> findActiveByIdWithDetails(@Param("id") Long id);
}
