package com.example.payment.customer.infrastructure.repository;

import com.example.payment.customer.application.CustomerRepository;
import com.example.payment.customer.domain.UserEntity;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JpaUserRepository implements CustomerRepository {
  private final JpaDataUserRepository jpaDataUserRepository;

  @Override
  public Optional<UserEntity> findById(Long id) {
    return jpaDataUserRepository.findActiveById(id);
  }

  @Override
  public List<UserEntity> findAll() {
    return jpaDataUserRepository.findAllActive();
  }

  @Override
  public boolean existsByEmailAndIdNot(String email, Long id) {
    return jpaDataUserRepository.existsByEmailAndIdNot(email, id);
  }

  @Override
  public boolean existsByEmail(String email) {
    return jpaDataUserRepository.existsByEmail(email);
  }

  @Override
  public UserEntity save(UserEntity customer) {
    return jpaDataUserRepository.save(customer);
  }
}
