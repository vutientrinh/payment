package com.example.payment.order.api.response;

import com.example.payment.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.util.List;

public record OrderResponse(
    Long id,
    String orderCode,
    OrderStatus status,
    BigDecimal orderAmount,
    Long customerId,
    List<OrderItemResponse> items) {
  public record OrderItemResponse(Long productId, Long quantity, BigDecimal price) {}
}
