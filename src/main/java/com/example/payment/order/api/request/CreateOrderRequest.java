package com.example.payment.order.api.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateOrderRequest {
  @NotBlank private String orderCode;
  @NotNull private Long customerId;
  @NotEmpty private List<@Valid CreateOrderItemRequest> items;
}
