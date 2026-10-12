package com.example.payment.order.domain;

import com.example.payment.product.domain.ProductEntity;
import com.example.payment.shared.domain.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(
    name = "order_items",
    indexes = @Index(name = "idx_order_items_order_id", columnList = "order_id"))
public class OrderItemEntity extends BaseEntity {
  private Long quantity;
  private BigDecimal price;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "order_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_order_items_order"))
  private OrderEntity order;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "product_id", nullable = false)
  private ProductEntity product;
}
