package com.example.payment.payment.domain;

import static com.example.payment.payment.domain.PaymentStatus.*;

import com.example.payment.order.domain.OrderEntity;
import com.example.payment.order.domain.OrderStatus;
import com.example.payment.shared.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
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

  @Setter(AccessLevel.NONE)
  @Column(name = "provider_reference", length = 128)
  private String providerReference;

  @Setter(AccessLevel.NONE)
  @Column(name = "failure_reason", length = 255)
  private String failureReason;

  @Version
  @Setter(AccessLevel.NONE)
  private Long version;

  public void transitionTo(PaymentStatus next) {
    if (!paymentStatus.canTransitionTo(next))
      throw new IllegalStateException("Payment Status cannot transition to PaymentStatus " + next);
    paymentStatus = next;
    order.setOrderStatus(toOrderStatus(next));
  }

  public void complete(String providerReference) {
    transitionTo(PaymentStatus.COMPLETED);
    this.providerReference = providerReference;
    this.failureReason = null;
  }

  public void fail(String providerReference, String failureReason) {
    transitionTo(PaymentStatus.FAILED);
    this.providerReference = providerReference;
    this.failureReason = failureReason;
  }

  public void requireReconciliation(String reason) {
    transitionTo(RECONCILIATION_REQUIRED);
    this.failureReason = reason;
  }

  private static OrderStatus toOrderStatus(PaymentStatus paymentStatus) {
    return switch (paymentStatus) {
      case PENDING, PROCESSING, RECONCILIATION_REQUIRED -> OrderStatus.PAYMENT_PROCESSING;
      case COMPLETED -> OrderStatus.PAID;
      case FAILED -> OrderStatus.PAYMENT_FAILED;
      case REFUNDED -> OrderStatus.REFUNDED;
    };
  }
}
