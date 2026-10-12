package com.example.payment.order.application;

import com.example.payment.customer.application.CustomerRepository;
import com.example.payment.customer.domain.UserEntity;
import com.example.payment.order.api.response.OrderResponse;
import com.example.payment.order.application.command.CreateOrderCommand;
import com.example.payment.order.application.command.CreateOrderItemCommand;
import com.example.payment.order.domain.OrderEntity;
import com.example.payment.order.domain.OrderItemEntity;
import com.example.payment.order.domain.OrderStatus;
import com.example.payment.product.application.ProductRepository;
import com.example.payment.product.domain.ProductEntity;
import com.example.payment.shared.application.ResourceNotFoundException;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

  private final OrderRepository orderRepository;
  private final CustomerRepository customerRepository;
  private final ProductRepository productRepository;

  @Override
  @Transactional(readOnly = true)
  public List<OrderResponse> getOrders() {
    return orderRepository.findAll().stream()
        .map(order -> new OrderResponse(order.getId(), order.getOrderCode()))
        .collect(Collectors.toList());
  }

  @Override
  public void createOrder(CreateOrderCommand createOrderCommand) {
    UserEntity userEntity =
        customerRepository
            .findById(createOrderCommand.customerId())
            .orElseThrow(
                () -> new ResourceNotFoundException("Customer", createOrderCommand.customerId()));
    OrderEntity orderEntity = new OrderEntity();
    orderEntity.setOrderCode(createOrderCommand.orderCode());
    orderEntity.setUser(userEntity);
    orderEntity.setOrderStatus(OrderStatus.PENDING);
    List<OrderItemEntity> items =
        createOrderCommand.items().stream()
            .map(itemRequest -> createOrderItem(orderEntity, itemRequest))
            .collect(Collectors.toList());
    BigDecimal totalPrice =
        items.stream()
            .map(item -> item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    orderEntity.setOrderAmount(totalPrice);
    orderEntity.setOrderItems(items);
    orderRepository.save(orderEntity);
  }

  private OrderItemEntity createOrderItem(
      OrderEntity order, CreateOrderItemCommand createOrderItemCommand) {
    ProductEntity product =
        productRepository
            .findById(createOrderItemCommand.productId())
            .orElseThrow(
                () -> new ResourceNotFoundException("Product", createOrderItemCommand.productId()));
    OrderItemEntity orderItemEntity = new OrderItemEntity();
    orderItemEntity.setOrder(order);
    orderItemEntity.setProduct(product);
    orderItemEntity.setQuantity(createOrderItemCommand.quantity());
    orderItemEntity.setPrice(product.getUnitPrice());
    return orderItemEntity;
  }
}
