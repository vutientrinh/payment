package com.example.payment.shared.inbox.infrastructure.repository;

import com.example.payment.shared.inbox.domain.ConsumedEventEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaDataConsumedEventRepository extends JpaRepository<ConsumedEventEntity, Long> {
  boolean existsByConsumerNameAndEventId(String consumerName, UUID eventId);
}
