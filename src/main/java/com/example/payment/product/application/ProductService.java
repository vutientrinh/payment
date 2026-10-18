package com.example.payment.product.application;

import com.example.payment.product.application.dto.ProductView;
import java.math.BigDecimal;
import java.util.List;

public interface ProductService {
  List<ProductView> getAllProducts();

  ProductView getProductById(Long id);

  ProductView createProduct(String productName, String productDescription, BigDecimal unitPrice);

  ProductView updateProduct(
      Long id, String productName, String productDescription, BigDecimal unitPrice);

  void deleteProduct(Long id);
}
