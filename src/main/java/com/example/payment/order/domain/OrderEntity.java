package com.example.payment.order.domain;

import com.example.payment.customer.domain.UserEntity;
import com.example.payment.shared.domain.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(
    name = "orders",
    indexes = @Index(name = "idx_orders_customer_id", columnList = "customer_id"))
public class OrderEntity extends BaseEntity {
  private String orderCode;

  @Enumerated(EnumType.STRING)
  private OrderStatus orderStatus;

  private BigDecimal orderAmount;
  private BigDecimal currency;

  @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<OrderItemEntity> orderItems = new ArrayList<>();

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "customer_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_orders_customer"))
  private UserEntity user;
}
