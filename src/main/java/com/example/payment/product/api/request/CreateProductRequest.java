package com.example.payment.product.api.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class CreateProductRequest {
  @NotNull private Long productId;
  @NotNull private String productName;
  @NotNull private String productDescription;
  @NotNull @Positive private BigDecimal unitPrice;
}
