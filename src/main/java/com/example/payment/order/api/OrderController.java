package com.example.payment.order.api;

import com.example.payment.order.api.request.CreateOrderRequest;
import com.example.payment.order.api.response.OrderResponse;
import com.example.payment.order.application.OrderService;
import com.example.payment.order.application.command.CreateOrderCommand;
import com.example.payment.order.application.command.CreateOrderItemCommand;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

  private final OrderService orderService;

  @GetMapping
  public ResponseEntity<List<OrderResponse>> getAllOrders() {
    return ResponseEntity.ok(orderService.getOrders());
  }

  @PostMapping
  public ResponseEntity<Void> createOrder(@Valid @RequestBody CreateOrderRequest request) {
    CreateOrderCommand command =
        new CreateOrderCommand(
            request.getOrderCode(),
            request.getCustomerId(),
            request.getItems().stream()
                .map(item -> new CreateOrderItemCommand(item.getProductId(), item.getQuantity()))
                .toList());
    orderService.createOrder(command);
    return ResponseEntity.status(HttpStatus.CREATED).build();
  }
}
