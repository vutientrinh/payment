package com.example.payment.product.application;

import com.example.payment.product.domain.ProductEntity;
import java.util.List;
import java.util.Optional;

public interface ProductRepository {
  List<ProductEntity> findAll();

  Optional<ProductEntity> findById(Long id);
}
