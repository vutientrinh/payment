package com.example.payment.product.application;

import com.example.payment.product.application.dto.ProductView;
import com.example.payment.product.domain.ProductEntity;
import com.example.payment.shared.application.ResourceNotFoundException;
import java.math.BigDecimal;
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
    return ProductView.from(getProduct(id));
  }

  @Override
  public ProductView createProduct(
      String productName, String productDescription, BigDecimal unitPrice) {
    ProductEntity product = new ProductEntity();
    applyChanges(product, productName, productDescription, unitPrice);
    return ProductView.from(productRepository.save(product));
  }

  @Override
  public ProductView updateProduct(
      Long id, String productName, String productDescription, BigDecimal unitPrice) {
    ProductEntity product = getProduct(id);
    applyChanges(product, productName, productDescription, unitPrice);
    return ProductView.from(product);
  }

  @Override
  public void deleteProduct(Long id) {
    getProduct(id).deactivate();
  }

  private ProductEntity getProduct(Long id) {
    return productRepository
        .findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Product", id));
  }

  private void applyChanges(
      ProductEntity product, String productName, String productDescription, BigDecimal unitPrice) {
    product.setProductName(productName);
    product.setProductDescription(productDescription);
    product.setUnitPrice(unitPrice);
  }
}
