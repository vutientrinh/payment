package com.example.payment.customer.infrastructure.repository;

import com.example.payment.customer.application.CustomerRepository;
import com.example.payment.customer.domain.UserEntity;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JpaUserRepository implements CustomerRepository {
  private final JpaDataUserRepository jpaDataUserRepository;

  @Override
  public Optional<UserEntity> findById(Long id) {
    return jpaDataUserRepository.findById(id);
  }

  @Override
  public UserEntity findByEmail(String email) {
    return jpaDataUserRepository.findByEmail(email);
  }
}
