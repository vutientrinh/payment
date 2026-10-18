package com.example.payment.shared.outbox.infrastructure.repository;

import com.example.payment.shared.outbox.domain.OutboxEventEntity;
import com.example.payment.shared.outbox.domain.OutboxEventStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaDataOutboxEventRepository extends JpaRepository<OutboxEventEntity, Long> {

  @Query(
      value =
          """
          select *
          from outbox_events
          where is_active = true
            and (
              (status = 'PENDING' and available_at <= :now)
              or (status = 'IN_PROGRESS' and lease_expires_at <= :now)
            )
          order by created_at
          limit :batchSize
          for update skip locked
          """,
      nativeQuery = true)
  List<OutboxEventEntity> findPublishableEventsForUpdate(
      @Param("now") LocalDateTime now, @Param("batchSize") int batchSize);

  Optional<OutboxEventEntity> findByIdAndStatusAndLeaseOwner(
      Long id, OutboxEventStatus status, String leaseOwner);
}
