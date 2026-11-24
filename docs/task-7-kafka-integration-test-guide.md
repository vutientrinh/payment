# Task 7 (part 1) — Kafka integration test: duplicate delivery never charges twice

> Goal: turn the manual Stage 4 demo and Stage 5 replay of Task 4 into one automated test that runs
> on every `mvnw test`. The code in this guide was compiled and run (16/16 green, about 20 s for the
> new class) on a copy of the repo on 2026-11-23.

## 0. What you build and why

The unit tests prove the executor **logic** with mocks. They cannot catch:

- a wrong `groupId` placeholder, a missing `ack.acknowledge()`, a wrong `ack-mode`
- a consumer that never starts (`payment.executor.enabled` off)
- JSON that the consumer cannot read back
- the inbox unique constraint, `@Version`, real transactions

This test starts **real PostgreSQL and real Kafka** in Docker (Testcontainers) and runs the whole path:

```
paymentService.createPayment()          (real service, real transaction, outbox row)
   → OutboxEventPublisher → Kafka payment.requested.v1
   → PaymentRequestedConsumer → PaymentExecutionServiceImpl → COMPLETED
then: send the SAME record again → wait until the consumer committed its offset
   → assert: 1 provider charge, same status, same version, 1 inbox row, 2 status events
```

Files:

| File | New / changed |
| --- | --- |
| `pom.xml` | add `testcontainers-kafka` |
| `src/test/java/com/example/payment/KafkaTestcontainersConfiguration.java` | new |
| `src/test/java/com/example/payment/payment/infrastructure/messaging/PaymentExecutorKafkaIntegrationTest.java` | new |

No production code changes.

---

## Step 1 — Dependency

`pom.xml`, next to `testcontainers-postgresql` (no version: the Spring Boot BOM manages it):

```xml
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-kafka</artifactId>
            <scope>test</scope>
        </dependency>
```

Awaitility (used to wait for async work) already comes with `spring-boot-starter-test`.

---

## Step 2 — Kafka container + topics

`src/test/java/com/example/payment/KafkaTestcontainersConfiguration.java`

```java
package com.example.payment;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** Real Kafka broker for integration tests + the topics the app expects. */
@TestConfiguration(proxyBeanMethods = false)
public class KafkaTestcontainersConfiguration {

  @Bean
  @ServiceConnection
  KafkaContainer kafkaContainer() {
    return new KafkaContainer(DockerImageName.parse("apache/kafka:4.4.0"));
  }

  @Bean
  NewTopic paymentRequestedTopic() {
    return TopicBuilder.name("payment.requested.v1").partitions(3).replicas(1).build();
  }

  @Bean
  NewTopic paymentStatusChangedTopic() {
    return TopicBuilder.name("payment.status-changed.v1").partitions(3).replicas(1).build();
  }
}
```

- `@ServiceConnection` makes Spring Boot point `spring.kafka.bootstrap-servers` at the container
  (same trick as the PostgreSQL bean in `TestcontainersConfiguration`).
- Same image as `docker-compose.yml`, so tests and local run use one Kafka version.
- `NewTopic` beans: Spring Boot's `KafkaAdmin` creates them at startup, like `kafka-init` in compose.
- Separate class (not added to `TestcontainersConfiguration`) so `PaymentApplicationTests` stays fast
  and does not start Kafka.

---

## Step 3 — The test

`src/test/java/com/example/payment/payment/infrastructure/messaging/PaymentExecutorKafkaIntegrationTest.java`

```java
package com.example.payment.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.example.payment.KafkaTestcontainersConfiguration;
import com.example.payment.TestcontainersConfiguration;
import com.example.payment.payment.application.PaymentProvider;
import com.example.payment.payment.application.PaymentService;
import com.example.payment.payment.domain.PaymentMethod;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(
    properties = {
      "payment.outbox.publisher.enabled=true",
      "payment.executor.enabled=true",
      "payment.provider.stub.latency=0s"
    })
@Import({TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class})
class PaymentExecutorKafkaIntegrationTest {
  private static final String REQUESTED_TOPIC = "payment.requested.v1";
  private static final String STATUS_CHANGED_TOPIC = "payment.status-changed.v1";
  private static final String CONSUMER_GROUP = "payment-executor-v1";
  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  @Autowired private PaymentService paymentService;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private KafkaTemplate<String, String> kafkaTemplate;
  @Autowired private KafkaAdmin kafkaAdmin;

  @MockitoSpyBean private PaymentProvider paymentProvider;

  @Test
  void paymentIsChargedOnceEvenWhenTheEventIsDeliveredTwice() throws Exception {
    Long paymentId = createPayment(new BigDecimal("120000.00"));

    // 1. First delivery: service -> outbox -> Kafka -> consumer -> executor -> COMPLETED.
    await().atMost(TIMEOUT).until(() -> "COMPLETED".equals(paymentStatus(paymentId)));
    assertThat(orderStatusOfPayment(paymentId)).isEqualTo("PAID");
    long versionBefore = paymentVersion(paymentId);

    // 2. Replay: send the exact same record again (same payload = same eventId, same key).
    String payload =
        jdbc.queryForObject(
            "select payload from outbox_events where event_type = ? and aggregate_id = ?",
            String.class,
            REQUESTED_TOPIC,
            paymentId);
    RecordMetadata replay =
        kafkaTemplate
            .send(REQUESTED_TOPIC, paymentId.toString(), payload)
            .get()
            .getRecordMetadata();

    // 3. Wait until the consumer has processed AND acked the replayed record.
    TopicPartition partition = new TopicPartition(REQUESTED_TOPIC, replay.partition());
    await().atMost(TIMEOUT).until(() -> committedOffset(partition) > replay.offset());

    // 4. Nothing changed: one charge, same status and version, one inbox row, two status events.
    verify(paymentProvider, times(1))
        .charge(eq(paymentId), any(), any(), eq("payment-" + paymentId));
    assertThat(paymentStatus(paymentId)).isEqualTo("COMPLETED");
    assertThat(paymentVersion(paymentId)).isEqualTo(versionBefore);
    assertThat(count("select count(*) from consumed_events where event_id = ?", eventIdOf(payload)))
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from outbox_events where event_type = ? and aggregate_id = ?",
                STATUS_CHANGED_TOPIC,
                paymentId))
        .isEqualTo(2); // PENDING->PROCESSING, PROCESSING->COMPLETED
  }

  @Test
  void amountAboveStubLimitEndsFailed() {
    Long paymentId = createPayment(new BigDecimal("12000000.00"));

    await().atMost(TIMEOUT).until(() -> "FAILED".equals(paymentStatus(paymentId)));
    assertThat(orderStatusOfPayment(paymentId)).isEqualTo("PAYMENT_FAILED");
  }

  // ---------- test data ----------

  /** Inserts customer, product and a PENDING order with SQL, then creates the payment for real. */
  private Long createPayment(BigDecimal amount) {
    String unique = UUID.randomUUID().toString();
    Long customerId =
        jdbc.queryForObject(
            """
            insert into users (first_name, last_name, email, phone_number, created_at, updated_at)
            values ('Test', 'User', ?, '0900000000', now(), now()) returning id
            """,
            Long.class,
            unique + "@example.com");
    Long productId =
        jdbc.queryForObject(
            """
            insert into products (product_name, product_description, unit_price, created_at, updated_at)
            values ('Coffee', 'Beans', ?, now(), now()) returning id
            """,
            Long.class,
            amount);
    Long orderId =
        jdbc.queryForObject(
            """
            insert into orders (order_code, order_status, order_amount, customer_id, created_at, updated_at)
            values (?, 'PENDING', ?, ?, now(), now()) returning id
            """,
            Long.class,
            "ORD-" + unique,
            amount,
            customerId);
    jdbc.update(
        """
        insert into order_items (quantity, price, order_id, product_id, created_at, updated_at)
        values (1, ?, ?, ?, now(), now())
        """,
        amount,
        orderId,
        productId);

    return paymentService.createPayment(orderId, PaymentMethod.EWALLET).id();
  }

  // ---------- reads ----------

  private String paymentStatus(Long paymentId) {
    return jdbc.queryForObject(
        "select payment_status from payments where id = ?", String.class, paymentId);
  }

  private String orderStatusOfPayment(Long paymentId) {
    return jdbc.queryForObject(
        "select o.order_status from orders o join payments p on p.order_id = o.id where p.id = ?",
        String.class,
        paymentId);
  }

  private long paymentVersion(Long paymentId) {
    return jdbc.queryForObject("select version from payments where id = ?", Long.class, paymentId);
  }

  private long count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Long.class, args);
  }

  private UUID eventIdOf(String payload) {
    return UUID.fromString(
        jdbc.queryForObject("select cast(? as jsonb) ->> 'eventId'", String.class, payload));
  }

  /** Next offset the consumer group will read on this partition (-1 = nothing committed yet). */
  private long committedOffset(TopicPartition partition) throws Exception {
    try (Admin admin = Admin.create(kafkaAdmin.getConfigurationProperties())) {
      OffsetAndMetadata committed =
          admin
              .listConsumerGroupOffsets(CONSUMER_GROUP)
              .partitionsToOffsetAndMetadata()
              .get()
              .get(partition);
      return committed == null ? -1 : committed.offset();
    }
  }
}
```

### Points to understand

- **`@SpringBootTest(properties = …)`** turns on the publisher and the consumer (both are off by
  default) and sets the stub latency to 0 so the test is fast. Different properties = a separate
  Spring context from `PaymentApplicationTests`; Spring caches each one.
- **`@MockitoSpyBean PaymentProvider`**: a *spy* wraps the real `StubPaymentProvider`. It still does
  the real work, but Mockito records every call, so `verify(..., times(1))` can count charges.
  (A `@Mock` would replace the real logic; a spy keeps it.) Spring resets the spy after each test.
- **Test data with SQL, payment through the service.** `OrderService.createOrder` returns `void`, so
  the test inserts customer/product/order directly. The part under test, `createPayment`, runs for
  real: real transaction, real outbox row. A random `unique` keeps the two tests independent.
- **Awaitility `await().until(...)`**: the work happens on the Kafka consumer thread, so the test
  polls (every 100 ms by default) until the condition is true, or fails after 30 s. Never use
  `Thread.sleep` for this: too short is flaky, too long is slow.
- **Why wait for the committed offset, not for a status?** After the replay, "nothing changed" is
  also true *before* the consumer reads the record, so checking the status could pass for the wrong
  reason. The consumer acks only after `execute()` returns (manual ack). The committed offset is the
  next offset to read, so `committed > replay.offset()` means the replay was fully handled.
- **`replay` uses the same key** (`paymentId`), so it lands on the same partition as the original,
  like a real redelivery.

---

## Step 4 — Run

Docker Desktop must be running.

```powershell
cd C:\WorkSpace\Code\Payment_Project\payment
.\mvnw.cmd spotless:apply
.\mvnw.cmd test -Dtest=PaymentExecutorKafkaIntegrationTest   # 2 tests, ~20-40 s (first run pulls apache/kafka)
.\mvnw.cmd clean test                                        # all 16 tests
```

| Error | Cause |
| --- | --- |
| `Could not find a valid Docker environment` | Docker Desktop is not running |
| `ConditionTimeout ... COMPLETED` after 30 s | consumer not running: check the `properties` block; or topic missing: check the `NewTopic` beans |
| `package org.testcontainers.kafka does not exist` | Step 1 missing, or IntelliJ did not reload Maven (click the Maven reload icon) |
| `Wanted 1 time but was 2` on `charge` | real bug: the duplicate was charged. Check the inbox check in `startProcessing` |
| Test passes in IntelliJ but not in Maven (or the reverse) | class name must end in `Test`/`Tests` for Surefire |

---

## Step 5 — Prove the test can fail (do this once)

A test you never saw fail proves nothing. Break the code on purpose, run the test, see red, undo:

| Break this (temporarily) | Expected failure |
| --- | --- |
| Comment out `acknowledgment.acknowledge();` in `PaymentRequestedConsumer` | `ConditionTimeout` on the committed offset: no ack = Kafka thinks the work is not done |
| Set `"payment.executor.enabled=false"` in the test | `ConditionTimeout` waiting for `COMPLETED` |

Undo each change and confirm green again.

---

## Done checklist

- [ ] `spotless:check test` green, 16 tests
- [ ] Saw at least one red run in Step 5, then green again
- [ ] README guides table: this guide → done
- [ ] Roadmap Task 7: Kafka duplicate-delivery case done

## Next

- **Task 5:** provider timeout = unknown outcome, reconciliation state, DLT instead of "log and skip".
  Its integration tests will reuse `KafkaTestcontainersConfiguration`.
- **TASK-002:** API gaps (`/products`, richer `PaymentResponse`, pagination, OpenAPI).
