package com.example.payment.order.application;

import com.example.payment.customer.application.CustomerRepository;
import com.example.payment.customer.domain.UserEntity;
import com.example.payment.order.application.command.CreateOrderCommand;
import com.example.payment.order.application.command.CreateOrderItemCommand;
import com.example.payment.order.application.dto.OrderView;
import com.example.payment.order.domain.OrderEntity;
import com.example.payment.order.domain.OrderItemEntity;
import com.example.payment.order.domain.OrderStatus;
import com.example.payment.payment.application.PaymentRepository;
import com.example.payment.product.application.ProductRepository;
import com.example.payment.product.domain.ProductEntity;
import com.example.payment.shared.application.ConflictException;
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
  private final PaymentRepository paymentRepository;

  @Override
  @Transactional(readOnly = true)
  public List<OrderView> getOrders() {
    return orderRepository.findAll().stream().map(OrderView::from).collect(Collectors.toList());
  }

  @Override
  @Transactional(readOnly = true)
  public OrderView getOrderById(Long id) {
    return OrderView.from(getOrder(id));
  }

  @Override
  public void createOrder(CreateOrderCommand createOrderCommand) {
    UserEntity userEntity = getCustomer(createOrderCommand.customerId());
    OrderEntity orderEntity = new OrderEntity();
    orderEntity.setUser(userEntity);
    orderEntity.setOrderStatus(OrderStatus.PENDING);
    applyChanges(orderEntity, createOrderCommand, userEntity);
    orderRepository.save(orderEntity);
  }

  @Override
  public OrderView updateOrder(Long id, CreateOrderCommand updateOrderCommand) {
    ensureOrderHasNoPayment(id);
    OrderEntity order = getOrder(id);
    applyChanges(order, updateOrderCommand, getCustomer(updateOrderCommand.customerId()));
    return OrderView.from(order);
  }

  @Override
  public void deleteOrder(Long id) {
    ensureOrderHasNoPayment(id);
    getOrder(id).deactivate();
  }

  private void applyChanges(OrderEntity order, CreateOrderCommand command, UserEntity customer) {
    order.setOrderCode(command.orderCode());
    order.setUser(customer);
    List<OrderItemEntity> items =
        command.items().stream()
            .map(item -> createOrderItem(order, item))
            .collect(Collectors.toList());
    BigDecimal totalPrice =
        items.stream()
            .map(item -> item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    order.getOrderItems().clear();
    order.getOrderItems().addAll(items);
    order.setOrderAmount(totalPrice);
  }

  private OrderEntity getOrder(Long id) {
    return orderRepository
        .findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Order", id));
  }

  private UserEntity getCustomer(Long customerId) {
    return customerRepository
        .findById(customerId)
        .orElseThrow(() -> new ResourceNotFoundException("Customer", customerId));
  }

  private void ensureOrderHasNoPayment(Long orderId) {
    if (paymentRepository.existsByOrderId(orderId)) {
      throw new ConflictException("Order cannot be changed after a payment has been created");
    }
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
