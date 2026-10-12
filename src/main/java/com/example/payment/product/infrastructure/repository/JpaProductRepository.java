package com.example.payment.product.infrastructure.repository;

import com.example.payment.product.application.ProductRepository;
import com.example.payment.product.domain.ProductEntity;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JpaProductRepository implements ProductRepository {
  private final JpaDataProductRepository jpaDataProductRepository;

  @Override
  public Optional<ProductEntity> findById(Long productId) {
    return jpaDataProductRepository.findById(productId);
  }

  @Override
  public List<ProductEntity> findAll() {
    return jpaDataProductRepository.findAll();
  }
}
