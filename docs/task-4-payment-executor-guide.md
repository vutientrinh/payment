# Task 4 — Idempotent Payment Executor (coding guide)

> Board: TASK-006. Roadmap: `docs/kafka-transactional-outbox-roadmap.md` → Task 4.
> Goal (P0 demo): `POST /payments` → watch the payment go **PENDING → PROCESSING → COMPLETED**
> (or FAILED) without anyone calling `PATCH /payments/{id}/status`.

## 0. What you build and why

```text
payment.requested.v1 (Kafka, key = paymentId)
   │
   ▼
PaymentRequestedConsumer            (infrastructure/messaging)  – parse envelope, ack AFTER success
   │
   ▼
PaymentExecutionServiceImpl         (application)
   ├─ TX 1  dedup check → PENDING → PROCESSING (+ status-changed outbox row)
   ├─ provider.charge(..., idempotencyKey = "payment-{id}")   ← NO DB transaction open here
   └─ TX 2  PROCESSING → COMPLETED/FAILED (+ outbox row) + insert consumed_events row
```

Three rules make it safe under at-least-once delivery:

1. **Inbox dedup**: `consumed_events(consumer_name, event_id)` is unique. An event already there is skipped.
2. **State guard**: only `PENDING → PROCESSING → COMPLETED|FAILED`. A payment that is already terminal is never charged.
3. **Provider idempotency key = `payment-{paymentId}`**: if we crash between "charge" and TX 2,
   the redelivered event charges again with the same key, and the provider returns the **same** result.

Why two transactions? A DB transaction cannot include an HTTP call to a provider. Holding a DB
connection open during a slow external call is also a classic outage cause.

Files you will touch (11 steps, in order):

| # | File | Action |
|---|------|--------|
| 1 | `db/migration/V4__payment_execution.sql` | new |
| 2 | `payment/domain/PaymentStatus.java`, `PaymentEntity.java` | edit |
| 3 | `payment/application/PaymentServiceImpl.java` | edit (use the domain rule) |
| 4 | `shared/inbox/**` | replace the 4 stubs |
| 5 | `payment/application/PaymentProviderResult.java`, `PaymentProvider.java` | edit |
| 6 | `payment/infrastructure/provider/StubPaymentProvider.java` | replace stub |
| 7 | `payment/application/event/PaymentStatusChangedEvent.java`, `PaymentStatusChangedOutbox.java` | new |
| 8 | `payment/application/command/ExecutePaymentCommand.java`, `PaymentExecutionService*.java` | replace stubs |
| 9 | `payment/infrastructure/messaging/PaymentRequestedConsumer.java` + Kafka config | replace stub / new |
| 10 | `application.properties`, `docker-compose.yml` | edit |
| 11 | tests | new + fix `PaymentApplicationTests` |

Paths below are relative to `src/main/java/com/example/payment/` unless they start with `src/`.

---

## Step 1 — Migration `V4__payment_execution.sql`

`src/main/resources/db/migration/V4__payment_execution.sql`

```sql
ALTER TABLE payments
    ADD COLUMN provider_reference VARCHAR(128),
    ADD COLUMN failure_reason     VARCHAR(255),
    ADD COLUMN version            BIGINT NOT NULL DEFAULT 0;
```

- `provider_reference`: the provider's transaction id, needed for refunds and reconciliation later.
- `version`: JPA optimistic lock. If two deliveries of the same event race (consumer rebalance),
  the second commit fails with `OptimisticLockException`, Kafka retries it, and the retry sees the
  inbox row and skips.

Never edit V1–V3: Flyway checksums would fail. Always add a new version.

---

## Step 2 — Put the state machine in the domain

Today the transition rule is a private method in `PaymentServiceImpl`. The executor needs the same
rule, so move it into the domain. Both callers then share it.

`payment/domain/PaymentStatus.java`

```java
package com.example.payment.payment.domain;

public enum PaymentStatus {
  PENDING,
  PROCESSING,
  COMPLETED,
  FAILED,
  REFUNDED;

  public boolean canTransitionTo(PaymentStatus next) {
    return switch (this) {
      case PENDING -> next == PROCESSING || next == FAILED;
      case PROCESSING -> next == COMPLETED || next == FAILED;
      case COMPLETED -> next == REFUNDED;
      case FAILED, REFUNDED -> false;
    };
  }
}
```

`payment/domain/PaymentEntity.java`: add the three fields and the behaviour methods.

```java
package com.example.payment.payment.domain;

import com.example.payment.order.domain.OrderEntity;
import com.example.payment.order.domain.OrderStatus;
import com.example.payment.shared.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "payments")
@Getter
@Setter
public class PaymentEntity extends BaseEntity {

  @Enumerated(EnumType.STRING)
  private PaymentMethod paymentMethod;

  @Enumerated(EnumType.STRING)
  private PaymentStatus paymentStatus;

  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "order_id",
      nullable = false,
      unique = true,
      foreignKey = @ForeignKey(name = "fk_payments_order"))
  private OrderEntity order;

  @Setter(AccessLevel.NONE)
  @Column(name = "provider_reference", length = 128)
  private String providerReference;

  @Setter(AccessLevel.NONE)
  @Column(name = "failure_reason", length = 255)
  private String failureReason;

  @Version
  @Setter(AccessLevel.NONE)
  private Long version;

  /** Moves the payment (and its order) to {@code next}; throws if the transition is illegal. */
  public void transitionTo(PaymentStatus next) {
    if (!paymentStatus.canTransitionTo(next)) {
      throw new IllegalStateException(
          "Payment " + id + " cannot transition from " + paymentStatus + " to " + next);
    }
    paymentStatus = next;
    order.setOrderStatus(toOrderStatus(next));
  }

  public void complete(String providerReference) {
    transitionTo(PaymentStatus.COMPLETED);
    this.providerReference = providerReference;
  }

  public void fail(String providerReference, String failureReason) {
    transitionTo(PaymentStatus.FAILED);
    this.providerReference = providerReference;
    this.failureReason = failureReason;
  }

  private static OrderStatus toOrderStatus(PaymentStatus status) {
    return switch (status) {
      case PENDING, PROCESSING -> OrderStatus.PAYMENT_PROCESSING;
      case COMPLETED -> OrderStatus.PAID;
      case FAILED -> OrderStatus.PAYMENT_FAILED;
      case REFUNDED -> OrderStatus.REFUNDED;
    };
  }
}
```

> Why `@Setter(AccessLevel.NONE)`: nobody should set `version` by hand, and the provider fields
> must only change together with the status (through `complete`/`fail`).

---

## Step 3 — Make `PaymentServiceImpl` use the domain rule

Replace `updatePaymentStatus` and **delete** the private `isTransitionAllowed` and `toOrderStatus` methods:

```java
  @Override
  public PaymentView updatePaymentStatus(Long id, PaymentStatus status) {
    PaymentEntity payment = getPayment(id);
    if (!payment.getPaymentStatus().canTransitionTo(status)) {
      throw new ConflictException(
          "Payment status cannot transition from " + payment.getPaymentStatus() + " to " + status);
    }
    payment.transitionTo(status);
    return PaymentView.from(payment);
  }
```

The constructor does not change, so `PaymentServiceImplTest` still compiles.

---

## Step 4 — Inbox (`shared/inbox`)

There are 4 empty stub classes. Make them real:

**Delete** `shared/inbox/domain/ConsumedEventRepository.java`. It duplicates the application port.

`shared/inbox/domain/ConsumedEventEntity.java`: add a factory below the fields (keep the rest):

```java
  public static ConsumedEventEntity of(String consumerName, java.util.UUID eventId) {
    ConsumedEventEntity entity = new ConsumedEventEntity();
    entity.consumerName = consumerName;
    entity.eventId = eventId;
    entity.consumedAt = LocalDateTime.now();
    return entity;
  }
```

`shared/inbox/application/ConsumedEventRepository.java`

```java
package com.example.payment.shared.inbox.application;

import com.example.payment.shared.inbox.domain.ConsumedEventEntity;
import java.util.UUID;

/** Inbox port: remembers which events a consumer already processed. */
public interface ConsumedEventRepository {
  boolean exists(String consumerName, UUID eventId);

  void save(ConsumedEventEntity consumedEvent);
}
```

`shared/inbox/infrastructure/repository/JpaDataConsumedEventRepository.java`

```java
package com.example.payment.shared.inbox.infrastructure.repository;

import com.example.payment.shared.inbox.domain.ConsumedEventEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaDataConsumedEventRepository extends JpaRepository<ConsumedEventEntity, Long> {
  boolean existsByConsumerNameAndEventId(String consumerName, UUID eventId);
}
```

`shared/inbox/infrastructure/repository/JpaConsumedEventRepository.java`

```java
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
  public void save(ConsumedEventEntity consumedEvent) {
    jpaDataConsumedEventRepository.save(consumedEvent);
  }
}
```

> The `exists` check is the fast path. The unique constraint `uk_consumed_events_consumer_event`
> is the real guarantee. If two threads pass `exists` at the same time, one insert fails and its
> transaction rolls back.

---

## Step 5 — Provider port

`payment/application/PaymentProviderResult.java`

```java
package com.example.payment.payment.application;

/** Definitive provider answer. Unknown outcomes (timeouts) are Task 5 — today they throw. */
public record PaymentProviderResult(
    Outcome outcome, String providerReference, String failureReason) {

  public enum Outcome {
    SUCCEEDED,
    DECLINED
  }

  public static PaymentProviderResult succeeded(String providerReference) {
    return new PaymentProviderResult(Outcome.SUCCEEDED, providerReference, null);
  }

  public static PaymentProviderResult declined(String providerReference, String reason) {
    return new PaymentProviderResult(Outcome.DECLINED, providerReference, reason);
  }
}
```

`payment/application/PaymentProvider.java`: only rename the first parameter, for clarity:

```java
public interface PaymentProvider {
  PaymentProviderResult charge(
      Long paymentId, BigDecimal amount, PaymentMethod method, String idempotencyKey);
}
```

---

## Step 6 — `StubPaymentProvider`

`payment/infrastructure/provider/StubPaymentProvider.java`

```java
package com.example.payment.payment.infrastructure.provider;

import com.example.payment.payment.application.PaymentProvider;
import com.example.payment.payment.application.PaymentProviderResult;
import com.example.payment.payment.domain.PaymentMethod;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fake provider. Honors the idempotency key: the same key always returns the first result and
 * never "charges" twice. (In memory only — a real provider stores this on its side.)
 */
@Slf4j
@Component
public class StubPaymentProvider implements PaymentProvider {
  private final Map<String, PaymentProviderResult> resultsByKey = new ConcurrentHashMap<>();
  private final Duration latency;
  private final BigDecimal declineAbove;

  public StubPaymentProvider(
      @Value("${payment.provider.stub.latency:2s}") Duration latency,
      @Value("${payment.provider.stub.decline-above:10000000}") BigDecimal declineAbove) {
    this.latency = latency;
    this.declineAbove = declineAbove;
  }

  @Override
  public PaymentProviderResult charge(
      Long paymentId, BigDecimal amount, PaymentMethod method, String idempotencyKey) {
    PaymentProviderResult previous = resultsByKey.get(idempotencyKey);
    if (previous != null) {
      log.info("Stub provider: replay of key {} -> {}", idempotencyKey, previous.outcome());
      return previous;
    }

    sleep(latency); // lets you SEE "PROCESSING" in the UI during the demo
    return resultsByKey.computeIfAbsent(idempotencyKey, key -> decide(amount));
  }

  private PaymentProviderResult decide(BigDecimal amount) {
    String reference = "STUB-" + UUID.randomUUID();
    if (amount.compareTo(declineAbove) > 0) {
      return PaymentProviderResult.declined(reference, "Amount exceeds stub limit");
    }
    return PaymentProviderResult.succeeded(reference);
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while calling stub provider", e);
    }
  }
}
```

Demo rule: amount **> 10,000,000 VND → DECLINED**, otherwise **SUCCEEDED**.

---

## Step 7 — `payment.status-changed.v1` event (via the outbox)

`payment/application/event/PaymentStatusChangedEvent.java`

```java
package com.example.payment.payment.application.event;

import com.example.payment.payment.domain.PaymentStatus;

public record PaymentStatusChangedEvent(
    Long paymentId,
    Long orderId,
    PaymentStatus previousStatus,
    PaymentStatus status,
    String providerReference,
    String failureReason) {}
```

`payment/application/PaymentStatusChangedOutbox.java`: writes the event as an outbox row **in the
caller's transaction**. Your existing `OutboxEventPublisher` then sends it to Kafka.

```java
package com.example.payment.payment.application;

import com.example.payment.payment.application.event.PaymentStatusChangedEvent;
import com.example.payment.payment.domain.PaymentEntity;
import com.example.payment.payment.domain.PaymentStatus;
import com.example.payment.shared.messaging.EventEnvelope;
import com.example.payment.shared.outbox.application.OutboxEventRepository;
import com.example.payment.shared.outbox.domain.OutboxEventEntity;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
public class PaymentStatusChangedOutbox {
  static final String EVENT_TYPE = "payment.status-changed.v1";
  static final String TOPIC = "payment.status-changed.v1";

  private final OutboxEventRepository outboxEventRepository;
  private final JsonMapper jsonMapper;

  public void append(PaymentEntity payment, PaymentStatus previousStatus) {
    UUID eventId = UUID.randomUUID();
    String payload =
        jsonMapper.writeValueAsString(
            new EventEnvelope<>(
                eventId,
                EVENT_TYPE,
                payment.getId(),
                Instant.now().toString(),
                MDC.get("traceId"),
                new PaymentStatusChangedEvent(
                    payment.getId(),
                    payment.getOrder().getId(),
                    previousStatus,
                    payment.getPaymentStatus(),
                    payment.getProviderReference(),
                    payment.getFailureReason())));

    outboxEventRepository.save(
        OutboxEventEntity.pending(
            eventId,
            "Payment",
            payment.getId(),
            EVENT_TYPE,
            TOPIC,
            payment.getId().toString(), // key = paymentId → per-payment ordering
            payload));
  }
}
```

---

## Step 8 — The executor (the core of this task)

`payment/application/command/ExecutePaymentCommand.java`

```java
package com.example.payment.payment.application.command;

import java.util.UUID;

/** eventId = dedup key; paymentId = what to execute. Amount is re-read from the DB (source of truth). */
public record ExecutePaymentCommand(UUID eventId, Long paymentId) {}
```

`payment/application/PaymentExecutionService.java`

```java
package com.example.payment.payment.application;

import com.example.payment.payment.application.command.ExecutePaymentCommand;

public interface PaymentExecutionService {
  void execute(ExecutePaymentCommand command);
}
```

`payment/application/PaymentExecutionServiceImpl.java`

```java
package com.example.payment.payment.application;

import com.example.payment.payment.application.command.ExecutePaymentCommand;
import com.example.payment.payment.domain.PaymentEntity;
import com.example.payment.payment.domain.PaymentMethod;
import com.example.payment.payment.domain.PaymentStatus;
import com.example.payment.shared.application.ResourceNotFoundException;
import com.example.payment.shared.inbox.application.ConsumedEventRepository;
import com.example.payment.shared.inbox.domain.ConsumedEventEntity;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentExecutionServiceImpl implements PaymentExecutionService {
  static final String CONSUMER_NAME = "payment-executor";

  private final PaymentRepository paymentRepository;
  private final ConsumedEventRepository consumedEventRepository;
  private final PaymentProvider paymentProvider;
  private final PaymentStatusChangedOutbox statusChangedOutbox;
  private final TransactionTemplate transactionTemplate;

  /** What TX 1 hands to the provider call (read inside the TX, used outside it). */
  private record Charge(Long paymentId, BigDecimal amount, PaymentMethod method) {}

  @Override
  public void execute(ExecutePaymentCommand command) {
    // TX 1 — dedup + claim. Returns null when there is nothing to do.
    Charge charge = transactionTemplate.execute(status -> startProcessing(command));
    if (charge == null) {
      return;
    }

    // Outside any transaction. Safe to repeat thanks to the idempotency key.
    PaymentProviderResult result =
        paymentProvider.charge(
            charge.paymentId(), charge.amount(), charge.method(), idempotencyKey(charge.paymentId()));

    // TX 2 — record the outcome + outbox event + inbox row, atomically.
    transactionTemplate.executeWithoutResult(status -> finish(command, result));
  }

  private Charge startProcessing(ExecutePaymentCommand command) {
    if (consumedEventRepository.exists(CONSUMER_NAME, command.eventId())) {
      log.info("Event {} already consumed — skipping", command.eventId());
      return null;
    }

    PaymentEntity payment = loadPayment(command.paymentId());
    switch (payment.getPaymentStatus()) {
      case PENDING -> {
        payment.transitionTo(PaymentStatus.PROCESSING);
        statusChangedOutbox.append(payment, PaymentStatus.PENDING);
      }
      case PROCESSING ->
          // A previous attempt crashed after TX 1. Charge again: the provider dedups by key.
          log.warn("Payment {} already PROCESSING — resuming", payment.getId());
      default -> {
        // Terminal (COMPLETED/FAILED/REFUNDED): never charge. Remember the event and stop.
        log.info("Payment {} is {} — nothing to execute", payment.getId(), payment.getPaymentStatus());
        consumedEventRepository.save(ConsumedEventEntity.of(CONSUMER_NAME, command.eventId()));
        return null;
      }
    }
    return new Charge(
        payment.getId(), payment.getOrder().getOrderAmount(), payment.getPaymentMethod());
  }

  private void finish(ExecutePaymentCommand command, PaymentProviderResult result) {
    if (consumedEventRepository.exists(CONSUMER_NAME, command.eventId())) {
      return; // a parallel delivery finished first
    }

    PaymentEntity payment = loadPayment(command.paymentId());
    if (payment.getPaymentStatus() == PaymentStatus.PROCESSING) {
      switch (result.outcome()) {
        case SUCCEEDED -> payment.complete(result.providerReference());
        case DECLINED -> payment.fail(result.providerReference(), result.failureReason());
      }
      statusChangedOutbox.append(payment, PaymentStatus.PROCESSING);
      log.info("Payment {} -> {}", payment.getId(), payment.getPaymentStatus());
    } else {
      log.warn(
          "Payment {} changed to {} while charging — result not applied",
          payment.getId(),
          payment.getPaymentStatus());
    }
    consumedEventRepository.save(ConsumedEventEntity.of(CONSUMER_NAME, command.eventId()));
  }

  private PaymentEntity loadPayment(Long paymentId) {
    return paymentRepository
        .findById(paymentId)
        .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
  }

  static String idempotencyKey(Long paymentId) {
    return "payment-" + paymentId;
  }
}
```

Points to understand before you move on:

- **`TransactionTemplate`, not `@Transactional`.** `@Transactional` on a private method, or on a
  method called from inside the same class, does nothing, because Spring's proxy is bypassed.
  `TransactionTemplate` gives exact boundaries. Spring Boot auto-configures the bean.
- **No `save()` call.** `payment` is managed inside the TX, so dirty checking flushes it at commit.
  `@Version` is checked at that flush.
- **Every exit path writes the inbox row** (terminal case and `finish`) except the "resume
  PROCESSING" path, which writes it in TX 2.

---

## Step 9 — Kafka consumer

`payment/infrastructure/messaging/PaymentRequestedConsumer.java` (replace the stub):

```java
package com.example.payment.payment.infrastructure.messaging;

import com.example.payment.payment.application.PaymentExecutionService;
import com.example.payment.payment.application.command.ExecutePaymentCommand;
import com.example.payment.payment.application.event.PaymentRequestedEvent;
import com.example.payment.shared.messaging.EventEnvelope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.executor.enabled", havingValue = "true")
public class PaymentRequestedConsumer {
  private static final TypeReference<EventEnvelope<PaymentRequestedEvent>> ENVELOPE_TYPE =
      new TypeReference<>() {};

  private final JsonMapper jsonMapper;
  private final PaymentExecutionService paymentExecutionService;

  @KafkaListener(
      id = "payment-executor",
      topics = "payment.requested.v1",
      groupId = "${payment.executor.consumer-group}")
  public void onPaymentRequested(ConsumerRecord<String, String> record, Acknowledgment ack) {
    EventEnvelope<PaymentRequestedEvent> envelope =
        jsonMapper.readValue(record.value(), ENVELOPE_TYPE);
    try (MDC.MDCCloseable trace = MDC.putCloseable("traceId", envelope.traceId())) {
      log.info(
          "Received {} eventId={} paymentId={} partition={} offset={}",
          envelope.eventType(),
          envelope.eventId(),
          envelope.payload().paymentId(),
          record.partition(),
          record.offset());

      paymentExecutionService.execute(
          new ExecutePaymentCommand(envelope.eventId(), envelope.payload().paymentId()));
    }
    ack.acknowledge(); // only reached after TX 2 committed
  }
}
```

New file `payment/infrastructure/messaging/PaymentExecutorKafkaConfiguration.java`. It sets
retries with back-off and skips retries for bad JSON or missing payments (they would never succeed):

```java
package com.example.payment.payment.infrastructure.messaging;

import com.example.payment.shared.application.ResourceNotFoundException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

@Configuration
@ConditionalOnProperty(name = "payment.executor.enabled", havingValue = "true")
public class PaymentExecutorKafkaConfiguration {

  /** Spring Boot plugs this bean into the default listener container factory. */
  @Bean
  DefaultErrorHandler kafkaErrorHandler() {
    DefaultErrorHandler handler = new DefaultErrorHandler(new FixedBackOff(1_000L, 5L));
    handler.addNotRetryableExceptions(JacksonException.class, ResourceNotFoundException.class);
    return handler;
  }
}
```

After the 5 retries the record is logged and skipped, and the payment stays `PROCESSING`. Task 5
handles this later with a DLT and reconciliation. For now you will see it in the logs.

---

## Step 10 — Config

`src/main/resources/application.properties`: add these lines (keep everything else):

```properties
# Start from the beginning the first time the executor group connects (no lost early events).
spring.kafka.consumer.auto-offset-reset=earliest

# Stub provider: latency makes PROCESSING visible; amounts above the limit are declined.
payment.provider.stub.latency=${PAYMENT_PROVIDER_STUB_LATENCY:2s}
payment.provider.stub.decline-above=${PAYMENT_PROVIDER_STUB_DECLINE_ABOVE:10000000}
```

`docker-compose.yml`:

1. In `payment-local.environment` add `PAYMENT_EXECUTOR_ENABLED: "true"`.
2. Make sure `kafka-init.command` creates **both** topics (auto-create is off). This was already
   applied on 2026-11-16 while fixing the local stack, so only check that it looks like this:

```yaml
    command:
      - |
        for topic in payment.requested.v1 payment.status-changed.v1; do
          /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:19092 \
            --create --if-not-exists --topic "$$topic" --partitions 3 --replication-factor 1
        done
```

(`$$` is how Compose escapes `$`.)

---

## Step 11 — Tests

### 11a. Fix the existing Flyway test

`src/test/java/com/example/payment/PaymentApplicationTests.java`:
`.containsExactly("1", "2", "3")` → `.containsExactly("1", "2", "3", "4")`.

### 11b. Unit test for the executor

`src/test/java/com/example/payment/payment/application/PaymentExecutionServiceImplTest.java`

```java
package com.example.payment.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.payment.order.domain.OrderEntity;
import com.example.payment.order.domain.OrderStatus;
import com.example.payment.payment.application.command.ExecutePaymentCommand;
import com.example.payment.payment.domain.PaymentEntity;
import com.example.payment.payment.domain.PaymentMethod;
import com.example.payment.payment.domain.PaymentStatus;
import com.example.payment.shared.inbox.application.ConsumedEventRepository;
import com.example.payment.shared.inbox.domain.ConsumedEventEntity;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class PaymentExecutionServiceImplTest {
  private static final String CONSUMER = PaymentExecutionServiceImpl.CONSUMER_NAME;
  private static final BigDecimal AMOUNT = new BigDecimal("120000.00");

  @Mock private PaymentRepository paymentRepository;
  @Mock private ConsumedEventRepository consumedEventRepository;
  @Mock private PaymentProvider paymentProvider;
  @Mock private PaymentStatusChangedOutbox statusChangedOutbox;
  @Mock private PlatformTransactionManager transactionManager; // TransactionTemplate just runs the callback

  private PaymentExecutionServiceImpl service;
  private final UUID eventId = UUID.randomUUID();
  private final ExecutePaymentCommand command = new ExecutePaymentCommand(eventId, 99L);

  @BeforeEach
  void setUp() {
    service =
        new PaymentExecutionServiceImpl(
            paymentRepository,
            consumedEventRepository,
            paymentProvider,
            statusChangedOutbox,
            new TransactionTemplate(transactionManager));
  }

  @Test
  void successfulChargeCompletesPaymentAndOrder() {
    PaymentEntity payment = payment(PaymentStatus.PENDING);
    when(paymentRepository.findById(99L)).thenReturn(Optional.of(payment));
    when(paymentProvider.charge(99L, AMOUNT, PaymentMethod.EWALLET, "payment-99"))
        .thenReturn(PaymentProviderResult.succeeded("STUB-1"));

    service.execute(command);

    assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(payment.getOrder().getOrderStatus()).isEqualTo(OrderStatus.PAID);
    assertThat(payment.getProviderReference()).isEqualTo("STUB-1");
    verify(statusChangedOutbox, times(2)).append(eq(payment), any()); // PROCESSING, COMPLETED
    verify(consumedEventRepository).save(any(ConsumedEventEntity.class));
  }

  @Test
  void declinedChargeFailsPaymentAndOrder() {
    PaymentEntity payment = payment(PaymentStatus.PENDING);
    when(paymentRepository.findById(99L)).thenReturn(Optional.of(payment));
    when(paymentProvider.charge(any(), any(), any(), any()))
        .thenReturn(PaymentProviderResult.declined("STUB-2", "Amount exceeds stub limit"));

    service.execute(command);

    assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.FAILED);
    assertThat(payment.getOrder().getOrderStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
    assertThat(payment.getFailureReason()).isEqualTo("Amount exceeds stub limit");
  }

  @Test
  void duplicateEventIsSkippedWithoutCharging() {
    when(consumedEventRepository.exists(CONSUMER, eventId)).thenReturn(true);

    service.execute(command);

    verifyNoInteractions(paymentProvider, statusChangedOutbox);
    verify(paymentRepository, never()).findById(any());
  }

  @Test
  void terminalPaymentIsNeverCharged() {
    when(paymentRepository.findById(99L)).thenReturn(Optional.of(payment(PaymentStatus.COMPLETED)));

    service.execute(command);

    verifyNoInteractions(paymentProvider, statusChangedOutbox);
    verify(consumedEventRepository).save(any(ConsumedEventEntity.class));
  }

  @Test
  void processingPaymentIsResumedWithSameIdempotencyKey() {
    PaymentEntity payment = payment(PaymentStatus.PROCESSING);
    when(paymentRepository.findById(99L)).thenReturn(Optional.of(payment));
    when(paymentProvider.charge(99L, AMOUNT, PaymentMethod.EWALLET, "payment-99"))
        .thenReturn(PaymentProviderResult.succeeded("STUB-1"));

    service.execute(command);

    assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.COMPLETED);
    verify(statusChangedOutbox, times(1)).append(payment, PaymentStatus.PROCESSING);
  }

  private static PaymentEntity payment(PaymentStatus status) {
    OrderEntity order = new OrderEntity();
    ReflectionTestUtils.setField(order, "id", 42L);
    order.setOrderStatus(OrderStatus.PAYMENT_PROCESSING);
    order.setOrderAmount(AMOUNT);

    PaymentEntity payment = new PaymentEntity();
    ReflectionTestUtils.setField(payment, "id", 99L);
    payment.setOrder(order);
    payment.setPaymentMethod(PaymentMethod.EWALLET);
    payment.setPaymentStatus(status);
    return payment;
  }
}
```

### 11c. Unit test for the stub's idempotency

`src/test/java/com/example/payment/payment/infrastructure/provider/StubPaymentProviderTest.java`

```java
package com.example.payment.payment.infrastructure.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.payment.payment.application.PaymentProviderResult;
import com.example.payment.payment.domain.PaymentMethod;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class StubPaymentProviderTest {
  private final StubPaymentProvider provider =
      new StubPaymentProvider(Duration.ZERO, new BigDecimal("1000"));

  @Test
  void sameKeyReturnsSameResult() {
    PaymentProviderResult first =
        provider.charge(1L, new BigDecimal("10"), PaymentMethod.CARD, "payment-1");
    PaymentProviderResult second =
        provider.charge(1L, new BigDecimal("10"), PaymentMethod.CARD, "payment-1");

    assertThat(second).isEqualTo(first);
    assertThat(first.outcome()).isEqualTo(PaymentProviderResult.Outcome.SUCCEEDED);
  }

  @Test
  void amountAboveLimitIsDeclined() {
    PaymentProviderResult result =
        provider.charge(2L, new BigDecimal("1001"), PaymentMethod.CARD, "payment-2");

    assertThat(result.outcome()).isEqualTo(PaymentProviderResult.Outcome.DECLINED);
  }
}
```

> `Duration.ZERO`: `Thread.sleep(Duration.ZERO)` returns at once. Fine for tests.

### Run

```powershell
cd payment
.\mvnw.cmd spotless:apply
.\mvnw.cmd spotless:check test     # Docker must be running (Testcontainers)
```

---

## Step 12 — Demo it end to end

```bash
cd payment
docker compose down -v            # fresh DB + topics (V4 is new)
docker compose up --build -d
docker compose logs -f payment-local    # keep open in a second terminal

API=http://localhost:8081
curl -s -X POST $API/customers -H 'Content-Type: application/json' \
  -d '{"firstName":"An","lastName":"Nguyen","email":"an@example.com","phoneNumber":"0900000001"}'
curl -s -X POST $API/product -H 'Content-Type: application/json' \
  -d '{"productName":"Coffee","productDescription":"Beans","unitPrice":120000}'
curl -s -X POST $API/orders -H 'Content-Type: application/json' \
  -d '{"orderCode":"ORD-1","customerId":1,"items":[{"productId":1,"quantity":1}]}'
curl -s -X POST $API/payments -H 'Content-Type: application/json' \
  -d '{"orderId":1,"method":"EWALLET"}'

# poll: PENDING/PROCESSING for ~2 s, then COMPLETED
for i in 1 2 3 4 5; do curl -s $API/payments/1; echo; sleep 1; done
curl -s $API/orders/1             # orderStatus = PAID
```

**Decline path:** create an order with `"quantity":100` (12,000,000 VND > limit). The payment ends
`FAILED` and the order ends `PAYMENT_FAILED`.

**See the events:**

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:19092 --topic payment.status-changed.v1 --from-beginning
```

**Prove idempotency (the acceptance criterion):** replay every request again.

```bash
docker compose stop payment-local
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:19092 \
  --group payment-executor-v1 --topic payment.requested.v1 --reset-offsets --to-earliest --execute
docker compose start payment-local
docker compose logs payment-local | grep "already consumed"
```

Expected: one "already consumed — skipping" line per payment, no new rows in
`payment.status-changed.v1`, and statuses unchanged.

---

## Done checklist

- [ ] `spotless:check test` green (incl. Flyway test expecting 1–4)
- [ ] Happy path: PENDING → PROCESSING → COMPLETED, order PAID
- [ ] Decline path: FAILED / PAYMENT_FAILED with `failure_reason`
- [ ] Replay from earliest: no second charge, no new status events
- [ ] Update `docs/kafka-transactional-outbox-roadmap.md` Task 4 status → implemented

## Not in this task (next steps)

- **Task 5:** provider timeout = *unknown*, not failure. Add a reconciliation state + DLT.
- **TASK-002:** expose `providerReference`, `failureReason`, `amount`, timestamps in `PaymentResponse`;
  `/product` → `/products`; pagination; OpenAPI.
- **Task 7:** Testcontainers Kafka integration test that publishes the same record twice.
