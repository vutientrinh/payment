package com.example.payment.product.domain;

import com.example.payment.shared.domain.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "products")
public class ProductEntity extends BaseEntity {
  private String productName;

  @Column(columnDefinition = "TEXT")
  private String productDescription;

  private BigDecimal unitPrice;
}
