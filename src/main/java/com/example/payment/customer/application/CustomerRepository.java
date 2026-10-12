package com.example.payment.customer.application;

import com.example.payment.customer.domain.UserEntity;
import java.util.Optional;

public interface CustomerRepository {
  Optional<UserEntity> findById(Long id);

  UserEntity findByEmail(String email);
}
