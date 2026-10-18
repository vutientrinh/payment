package com.example.payment.product.infrastructure.repository;

import com.example.payment.product.domain.ProductEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaDataProductRepository extends JpaRepository<ProductEntity, Long> {

  @Query("select product from ProductEntity product where product.isActive = true")
  List<ProductEntity> findAllActive();

  @Query(
      "select product from ProductEntity product where product.id = :id and product.isActive = true")
  Optional<ProductEntity> findActiveById(@Param("id") Long id);
}
