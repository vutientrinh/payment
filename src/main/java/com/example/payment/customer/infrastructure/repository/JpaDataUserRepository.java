package com.example.payment.customer.infrastructure.repository;

import com.example.payment.customer.domain.UserEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface JpaDataUserRepository extends JpaRepository<UserEntity, Long> {

  @Query("select customer from UserEntity customer where customer.isActive = true")
  List<UserEntity> findAllActive();

  @Query(
      "select customer from UserEntity customer where customer.id = :id and customer.isActive = true")
  Optional<UserEntity> findActiveById(@Param("id") Long id);

  boolean existsByEmail(String email);

  boolean existsByEmailAndIdNot(String email, Long id);
}
