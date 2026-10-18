package com.example.payment.customer.application;

import com.example.payment.customer.domain.UserEntity;
import java.util.List;
import java.util.Optional;

public interface CustomerRepository {
  Optional<UserEntity> findById(Long id);

  List<UserEntity> findAll();

  boolean existsByEmailAndIdNot(String email, Long id);

  boolean existsByEmail(String email);

  UserEntity save(UserEntity customer);
}
