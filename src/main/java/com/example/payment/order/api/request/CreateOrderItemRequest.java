package com.example.payment.order.api.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class CreateOrderItemRequest {
  @NotNull private Long productId;
  @NotNull @Positive private Long quantity;
}
