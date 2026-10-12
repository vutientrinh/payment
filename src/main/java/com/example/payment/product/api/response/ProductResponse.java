package com.example.payment.product.api.response;

import java.math.BigDecimal;
import lombok.Getter;

@Getter
public class ProductResponse {
  private Long productId;
  private String productName;
  private String productDescription;
  private BigDecimal productPrice;
}
