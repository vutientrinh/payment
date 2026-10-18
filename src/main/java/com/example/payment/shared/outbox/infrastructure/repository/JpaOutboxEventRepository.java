package com.example.payment.shared.outbox.infrastructure.repository;

import com.example.payment.shared.outbox.application.OutboxEventRepository;
import com.example.payment.shared.outbox.domain.OutboxEventEntity;
import com.example.payment.shared.outbox.domain.OutboxEventStatus;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class JpaOutboxEventRepository implements OutboxEventRepository {
  private final JpaDataOutboxEventRepository jpaDataOutboxEventRepository;

  @Override
  public OutboxEventEntity save(OutboxEventEntity event) {
    return jpaDataOutboxEventRepository.save(event);
  }

  @Override
  @Transactional
  public List<OutboxEventEntity> claimPendingBatch(
      String leaseOwner, LocalDateTime now, LocalDateTime leaseExpiresAt, int batchSize) {
    List<OutboxEventEntity> events =
        jpaDataOutboxEventRepository.findPublishableEventsForUpdate(now, batchSize);
    events.forEach(event -> event.claim(leaseOwner, leaseExpiresAt));
    return events;
  }

  @Override
  @Transactional
  public boolean markPublished(Long eventId, String leaseOwner, LocalDateTime publishedAt) {
    return jpaDataOutboxEventRepository
        .findByIdAndStatusAndLeaseOwner(eventId, OutboxEventStatus.IN_PROGRESS, leaseOwner)
        .map(
            event -> {
              event.markPublished(publishedAt);
              return true;
            })
        .orElse(false);
  }

  @Override
  @Transactional
  public boolean recordPublishFailure(
      Long eventId, String leaseOwner, LocalDateTime availableAt, String error, int maxAttempts) {
    return jpaDataOutboxEventRepository
        .findByIdAndStatusAndLeaseOwner(eventId, OutboxEventStatus.IN_PROGRESS, leaseOwner)
        .map(
            event -> {
              if (event.getRetryCount() + 1 >= maxAttempts) {
                event.markFailed(error);
              } else {
                event.reschedule(availableAt, error);
              }
              return true;
            })
        .orElse(false);
  }
}
