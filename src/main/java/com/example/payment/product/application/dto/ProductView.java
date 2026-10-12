package com.example.payment.product.application.dto;

import com.example.payment.product.domain.ProductEntity;
import java.math.BigDecimal;

public record ProductView(
    Long id, String productName, String productDescription, BigDecimal unitPrice) {

  public static ProductView from(ProductEntity product) {
    return new ProductView(
        product.getId(),
        product.getProductName(),
        product.getProductDescription(),
        product.getUnitPrice());
  }
}
