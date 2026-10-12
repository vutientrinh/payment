package com.example.payment.payment.domain;

import com.example.payment.order.domain.OrderEntity;
import com.example.payment.shared.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "payments")
@Getter
@Setter
public class PaymentEntity extends BaseEntity {

  @Enumerated(EnumType.STRING)
  private PaymentMethod paymentMethod;

  @Enumerated(EnumType.STRING)
  private PaymentStatus paymentStatus;

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "order_id",
      nullable = false,
      unique = true,
      foreignKey = @ForeignKey(name = "fk_payments_order"))
  private OrderEntity order;
}
