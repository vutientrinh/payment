package com.example.payment.customer.infrastructure.repository;

import com.example.payment.customer.domain.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface JpaDataUserRepository extends JpaRepository<UserEntity, Long> {
  UserEntity findByEmail(String email);
}
