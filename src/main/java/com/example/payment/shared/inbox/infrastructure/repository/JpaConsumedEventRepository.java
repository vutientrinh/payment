package com.example.payment.shared.inbox.infrastructure.repository;

import com.example.payment.shared.inbox.application.ConsumedEventRepository;
import com.example.payment.shared.inbox.domain.ConsumedEventEntity;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JpaConsumedEventRepository implements ConsumedEventRepository {
  private final JpaDataConsumedEventRepository jpaDataConsumedEventRepository;

  @Override
  public boolean exists(String consumerName, UUID eventId) {
    return jpaDataConsumedEventRepository.existsByConsumerNameAndEventId(consumerName, eventId);
  }

  @Override
  public void save(ConsumedEventEntity consumedEventEntity) {
    jpaDataConsumedEventRepository.save(consumedEventEntity);
  }
}
