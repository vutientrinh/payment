package com.example.payment.order.application;

import com.example.payment.order.api.response.OrderResponse;
import com.example.payment.order.application.command.CreateOrderCommand;
import java.util.List;

public interface OrderService {
  List<OrderResponse> getOrders();

  void createOrder(CreateOrderCommand createOrderCommand);
}
