package com.example.payment.product.application;

import com.example.payment.product.application.dto.ProductView;
import com.example.payment.shared.application.ResourceNotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {
  private final ProductRepository productRepository;

  @Override
  @Transactional(readOnly = true)
  public List<ProductView> getAllProducts() {
    return productRepository.findAll().stream().map(ProductView::from).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public ProductView getProductById(Long id) {
    return ProductView.from(
        productRepository
            .findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Product", id)));
  }
}
