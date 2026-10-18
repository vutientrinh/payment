package com.example.payment.order.application.dto;

import com.example.payment.order.domain.OrderEntity;
import com.example.payment.order.domain.OrderItemEntity;
import com.example.payment.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.util.List;

public record OrderView(
    Long id,
    String orderCode,
    OrderStatus status,
    BigDecimal orderAmount,
    Long customerId,
    List<OrderItemView> items) {

  public static OrderView from(OrderEntity order) {
    return new OrderView(
        order.getId(),
        order.getOrderCode(),
        order.getOrderStatus(),
        order.getOrderAmount(),
        order.getUser().getId(),
        order.getOrderItems().stream().map(OrderItemView::from).toList());
  }

  public record OrderItemView(Long productId, Long quantity, BigDecimal price) {
    private static OrderItemView from(OrderItemEntity item) {
      return new OrderItemView(item.getProduct().getId(), item.getQuantity(), item.getPrice());
    }
  }
}
