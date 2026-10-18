package com.example.payment.product.api;

import com.example.payment.product.api.request.CreateProductRequest;
import com.example.payment.product.api.request.UpdateProductRequest;
import com.example.payment.product.api.response.ProductResponse;
import com.example.payment.product.application.ProductService;
import com.example.payment.product.application.dto.ProductView;
import jakarta.validation.Valid;
import java.util.List;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/product")
@AllArgsConstructor
public class ProductController {

  private final ProductService productService;

  @GetMapping
  public ResponseEntity<List<ProductResponse>> getAllProducts() {
    List<ProductResponse> products =
        productService.getAllProducts().stream().map(ProductController::toResponse).toList();
    return ResponseEntity.ok(products);
  }

  @GetMapping("/{id}")
  public ResponseEntity<ProductResponse> getProduct(@PathVariable Long id) {
    return ResponseEntity.ok(toResponse(productService.getProductById(id)));
  }

  @PostMapping
  public ResponseEntity<ProductResponse> createProduct(
      @Valid @RequestBody CreateProductRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            toResponse(
                productService.createProduct(
                    request.productName(), request.productDescription(), request.unitPrice())));
  }

  @PutMapping("/{id}")
  public ResponseEntity<ProductResponse> updateProduct(
      @PathVariable Long id, @Valid @RequestBody UpdateProductRequest request) {
    return ResponseEntity.ok(
        toResponse(
            productService.updateProduct(
                id, request.productName(), request.productDescription(), request.unitPrice())));
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> deleteProduct(@PathVariable Long id) {
    productService.deleteProduct(id);
    return ResponseEntity.noContent().build();
  }

  private static ProductResponse toResponse(ProductView product) {
    return new ProductResponse(
        product.id(), product.productName(), product.productDescription(), product.unitPrice());
  }
}
