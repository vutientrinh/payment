package com.example.payment.product.application;

import com.example.payment.product.application.dto.ProductView;
import java.util.List;

public interface ProductService {
  List<ProductView> getAllProducts();

  ProductView getProductById(Long id);
}
