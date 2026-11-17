package com.example.payment.shared.inbox.application;

import com.example.payment.shared.inbox.domain.ConsumedEventEntity;
import java.util.UUID;

public interface ConsumedEventRepository {
  boolean exists(String consumerName, UUID eventId);

  void save(ConsumedEventEntity consumedEventEntity);
}
