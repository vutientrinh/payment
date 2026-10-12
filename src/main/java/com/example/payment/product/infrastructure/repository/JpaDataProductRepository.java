package com.example.payment.product.infrastructure.repository;

import com.example.payment.product.domain.ProductEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaDataProductRepository extends JpaRepository<ProductEntity, Long> {}
