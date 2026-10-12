package com.example.payment.customer.domain;

import com.example.payment.shared.domain.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class UserEntity extends BaseEntity {
  private String firstName;
  private String lastName;
  private String email;
  private String phoneNumber;
}
