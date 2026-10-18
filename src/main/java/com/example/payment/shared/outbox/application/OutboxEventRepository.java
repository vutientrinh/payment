package com.example.payment.shared.outbox.application;

import com.example.payment.shared.outbox.domain.OutboxEventEntity;
import java.time.LocalDateTime;
import java.util.List;

public interface OutboxEventRepository {
  OutboxEventEntity save(OutboxEventEntity event);

  List<OutboxEventEntity> claimPendingBatch(
      String leaseOwner, LocalDateTime now, LocalDateTime leaseExpiresAt, int batchSize);

  boolean markPublished(Long eventId, String leaseOwner, LocalDateTime publishedAt);

  boolean recordPublishFailure(
      Long eventId, String leaseOwner, LocalDateTime availableAt, String error, int maxAttempts);
}
