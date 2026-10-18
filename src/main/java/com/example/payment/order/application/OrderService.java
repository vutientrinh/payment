package com.example.payment.order.application;

import com.example.payment.order.application.command.CreateOrderCommand;
import com.example.payment.order.application.dto.OrderView;
import java.util.List;

public interface OrderService {
  List<OrderView> getOrders();

  OrderView getOrderById(Long id);

  void createOrder(CreateOrderCommand createOrderCommand);

  OrderView updateOrder(Long id, CreateOrderCommand updateOrderCommand);

  void deleteOrder(Long id);
}
