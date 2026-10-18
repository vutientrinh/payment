package com.example.payment.order.api;

import com.example.payment.order.api.request.CreateOrderRequest;
import com.example.payment.order.api.request.UpdateOrderRequest;
import com.example.payment.order.api.response.OrderResponse;
import com.example.payment.order.application.OrderService;
import com.example.payment.order.application.command.CreateOrderCommand;
import com.example.payment.order.application.command.CreateOrderItemCommand;
import com.example.payment.order.application.dto.OrderView;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
    List<OrderResponse> orders =
        orderService.getOrders().stream().map(OrderController::toResponse).toList();
    return ResponseEntity.ok(orders);
  }

  @GetMapping("/{id}")
  public ResponseEntity<OrderResponse> getOrder(@PathVariable Long id) {
    return ResponseEntity.ok(toResponse(orderService.getOrderById(id)));
  }

  @PostMapping
  public ResponseEntity<Void> createOrder(@Valid @RequestBody CreateOrderRequest request) {
    CreateOrderCommand command =
        new CreateOrderCommand(
            request.orderCode(),
            request.customerId(),
            request.items().stream()
                .map(item -> new CreateOrderItemCommand(item.productId(), item.quantity()))
                .toList());
    orderService.createOrder(command);
    return ResponseEntity.status(HttpStatus.CREATED).build();
  }

  @PutMapping("/{id}")
  public ResponseEntity<OrderResponse> updateOrder(
      @PathVariable Long id, @Valid @RequestBody UpdateOrderRequest request) {
    return ResponseEntity.ok(
        toResponse(
            orderService.updateOrder(
                id,
                new CreateOrderCommand(
                    request.orderCode(),
                    request.customerId(),
                    request.items().stream()
                        .map(item -> new CreateOrderItemCommand(item.productId(), item.quantity()))
                        .toList()))));
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> deleteOrder(@PathVariable Long id) {
    orderService.deleteOrder(id);
    return ResponseEntity.noContent().build();
  }

  private static OrderResponse toResponse(OrderView order) {
    return new OrderResponse(
        order.id(),
        order.orderCode(),
        order.status(),
        order.orderAmount(),
        order.customerId(),
        order.items().stream()
            .map(
                item ->
                    new OrderResponse.OrderItemResponse(
                        item.productId(), item.quantity(), item.price()))
            .toList());
  }
}
