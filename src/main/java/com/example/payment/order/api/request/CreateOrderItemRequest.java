package com.example.payment.order.api.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateOrderItemRequest(@NotNull Long productId, @NotNull @Positive Long quantity) {}
