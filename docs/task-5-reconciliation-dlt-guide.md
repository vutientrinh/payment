# Task 5 — Unknown provider outcomes, reconciliation and the dead letter topic (coding guide)

> Code in this guide was applied to a copy of the repo (state of 2026-11-24, after Task 7 part 1)
> and run: **26/26 tests green** (16 old + 10 new). Follow the stages in order; each ends with a check.

## 0. What you build and why

Task 4 left three holes:

| Hole | What happens today | After Task 5 |
| --- | --- | --- |
| Provider **times out** (charge may have happened) | exception → Kafka retries → after retries the record is dropped, payment stuck in `PROCESSING` | payment → `RECONCILIATION_REQUIRED`; a job **asks** the provider (never charges again) and records the answer |
| Executor crashed after TX 1 / gave up | payment stuck in `PROCESSING` forever | the same job finds `PROCESSING` older than 5 min and resolves it |
| **Poison message** (bad JSON, unknown payment) | logged and dropped: nobody can see or replay it | parked on topic `payment.requested.v1.dlt` with the error in its headers |

The one rule to remember: **a timeout is not a failure.** "I don't know" must never become "charge
again" (double charge) or "FAILED" (the customer paid, we say they did not). The only safe move is
to *look the result up* by the idempotency key.

```
                       charge(payment-42)
executor ──TX1 PROCESSING──► provider ──✗ timeout (response lost; money may be taken)
   │
   └─TX2 RECONCILIATION_REQUIRED + status event + inbox row   (ack Kafka; never charge again)

reconciliation job (every 10 s)
   ├─ find RECONCILIATION_REQUIRED (older than 30 s) + PROCESSING (older than 5 min)
   ├─ TX1  PROCESSING → RECONCILIATION_REQUIRED (only for stuck ones)
   ├─ provider.findByIdempotencyKey("payment-42")   ← LOOKUP, not a charge
   └─ TX2  found SUCCEEDED → COMPLETED / found DECLINED → FAILED / not found → FAILED
```

Design choices:

- **A scheduled DB poller**, not a `payment.reconciliation-requested.v1` event. The DB is the source
  of truth and the poller also catches payments that never got an event (crash after TX 1).
- **No migration.** `payment_status` is `VARCHAR(32)` and `RECONCILIATION_REQUIRED` is 23 chars;
  `updated_at` already exists. (So the security guide keeps `V5`.)
- **The order stays `PAYMENT_PROCESSING`** while reconciling: for the shopper it is still "in progress".

Files:

| Stage | File | New / changed |
| --- | --- | --- |
| 1 | `payment/domain/PaymentStatus.java`, `PaymentEntity.java` | changed |
| 2 | `payment/application/ProviderOutcomeUnknownException.java` | new |
| 2 | `payment/application/PaymentProvider.java`, `infrastructure/provider/StubPaymentProvider.java` | changed |
| 2 | test `StubPaymentProviderTest.java` | changed |
| 3 | `payment/application/PaymentExecutionServiceImpl.java` + its test | changed |
| 4 | `PaymentRepository.java`, `JpaDataPaymentRepository.java`, `JpaPaymentRepository.java` | changed |
| 4 | `PaymentReconciliationService.java`, `PaymentReconciliationServiceImpl.java` | new |
| 4 | `payment/infrastructure/scheduling/PaymentReconciliationJob.java` | new |
| 4 | test `PaymentReconciliationServiceImplTest.java` | new |
| 5 | `infrastructure/messaging/PaymentExecutorKafkaConfiguration.java` | changed |
| 5 | `application.properties`, `docker-compose.yml`, test `KafkaTestcontainersConfiguration.java` | changed |
| 6 | test `PaymentFailureHandlingIntegrationTest.java` | new |

Paths below start at `src/main/java/com/example/payment/` unless they say `src/test`.

---

## Stage 1 — New state in the domain

### 1.1 `payment/domain/PaymentStatus.java`

```java
public enum PaymentStatus {
  PENDING,
  PROCESSING,
  RECONCILIATION_REQUIRED,
  COMPLETED,
  FAILED,
  REFUNDED;

  public boolean canTransitionTo(PaymentStatus to) {
    return switch (this) {
      case PENDING -> to == PROCESSING || to == FAILED;
      case PROCESSING -> to == COMPLETED || to == FAILED || to == RECONCILIATION_REQUIRED;
      case RECONCILIATION_REQUIRED -> to == COMPLETED || to == FAILED;
      case COMPLETED -> to == REFUNDED;
      case FAILED, REFUNDED -> false;
    };
  }
}
```

### 1.2 `payment/domain/PaymentEntity.java`

Change `complete`, add `requireReconciliation`, extend `toOrderStatus`:

```java
  public void complete(String providerReference) {
    transitionTo(PaymentStatus.COMPLETED);
    this.providerReference = providerReference;
    this.failureReason = null; // clear the "timeout" note left by reconciliation
  }

  /** Outcome unknown (timeout, lost response): never charge again, ask the provider later. */
  public void requireReconciliation(String reason) {
    transitionTo(PaymentStatus.RECONCILIATION_REQUIRED);
    this.failureReason = reason;
  }
```

```java
      case PENDING, PROCESSING, RECONCILIATION_REQUIRED -> OrderStatus.PAYMENT_PROCESSING;
```

Before you add the last line, compile once (`.\mvnw.cmd compile`). The `switch` in `toOrderStatus` has
no `default`, so Java refuses to compile: *"the switch expression does not cover all possible input
values"*. This is why we wrote it without `default`: a new status cannot be forgotten.

**Check:** `.\mvnw.cmd test` → still 16 green.

---

## Stage 2 — Provider contract: "unknown" + lookup

### 2.1 New `payment/application/ProviderOutcomeUnknownException.java`

```java
package com.example.payment.payment.application;

/**
 * The provider call ended without a definitive answer (timeout, connection lost after sending). The
 * charge MAY have happened. Never charge again: look the result up by idempotency key later.
 */
public class ProviderOutcomeUnknownException extends RuntimeException {
  public ProviderOutcomeUnknownException(String message) {
    super(message);
  }
}
```

A dedicated exception type, because the executor must react to *this* case differently from every
other error. A real HTTP adapter would throw it on read timeout or connection reset after the request
was sent (but **not** on "connection refused": then nothing was sent).

### 2.2 `payment/application/PaymentProvider.java`

```java
package com.example.payment.payment.application;

import com.example.payment.payment.domain.PaymentMethod;
import java.math.BigDecimal;
import java.util.Optional;

public interface PaymentProvider {
  /**
   * Charges once per idempotency key. Throws {@link ProviderOutcomeUnknownException} when the
   * result is unknown (the charge may or may not have happened).
   */
  PaymentProviderResult charge(
      Long paymentId, BigDecimal amount, PaymentMethod method, String idempotencyKey);

  /** Asks the provider what happened to a charge. Empty = the provider never received it. */
  Optional<PaymentProviderResult> findByIdempotencyKey(String idempotencyKey);
}
```

Real providers (Stripe, VNPay, MoMo…) all have such a "get transaction by reference" call. This is
why the idempotency key `payment-{id}` matters: it is the handle to ask "what happened to *this* one?".

### 2.3 `payment/infrastructure/provider/StubPaymentProvider.java`

Add a third constructor parameter, the timeout branch and the lookup:

```java
  private final BigDecimal timeoutAmount;

  public StubPaymentProvider(
      @Value("${payment.provider.stub.latency:2s}") Duration latency,
      @Value("${payment.provider.stub.decline-above:10000000}") BigDecimal declinedAbove,
      @Value("${payment.provider.stub.timeout-amount:7777000}") BigDecimal timeoutAmount) {
    this.latency = latency;
    this.declinedAbove = declinedAbove;
    this.timeoutAmount = timeoutAmount;
  }
```

In `charge`, replace the last line (`return resultsByKey.computeIfAbsent(...)`) with:

```java
    PaymentProviderResult result =
        resultsByKey.computeIfAbsent(idempotencyKey, key -> decide(amount));

    if (amount.compareTo(timeoutAmount) == 0) {
      // Demo of the worst case: the charge HAPPENED, but the response is lost on the way back.
      log.warn("Stub provider: charged key {} but simulating a timeout", idempotencyKey);
      throw new ProviderOutcomeUnknownException("Stub provider timeout (charge was made)");
    }
    return result;
  }

  @Override
  public Optional<PaymentProviderResult> findByIdempotencyKey(String idempotencyKey) {
    return Optional.ofNullable(resultsByKey.get(idempotencyKey));
  }
```

Imports: `com.example.payment.payment.application.ProviderOutcomeUnknownException`, `java.util.Optional`.

The stub stores the result **before** throwing. That is the dangerous real-world case: money taken,
answer lost. If the executor charged again, a provider without idempotency would charge twice; if it
marked FAILED, the shopper paid for nothing.

### 2.4 `src/test/.../infrastructure/provider/StubPaymentProviderTest.java`

The constructor has 3 parameters now. Change the field and add two tests:

```java
  private final StubPaymentProvider provider =
      new StubPaymentProvider(Duration.ZERO, new BigDecimal("1000"), new BigDecimal("777"));
```

```java
  @Test
  void timeoutAmountChargesButLosesTheResponse() {
    assertThatThrownBy(
            () -> provider.charge(3L, new BigDecimal("777"), PaymentMethod.CARD, "payment-3"))
        .isInstanceOf(ProviderOutcomeUnknownException.class);

    // The charge happened: a lookup by key finds it.
    assertThat(provider.findByIdempotencyKey("payment-3"))
        .hasValueSatisfying(
            result ->
                assertThat(result.outcome()).isEqualTo(PaymentProviderResult.Outcome.SUCCEEDED));
  }

  @Test
  void unknownKeyIsNotFound() {
    assertThat(provider.findByIdempotencyKey("payment-404")).isEmpty();
  }
```

Imports: `static org.assertj.core.api.Assertions.assertThatThrownBy`,
`com.example.payment.payment.application.ProviderOutcomeUnknownException`.

`assertThatThrownBy(() -> ...)` runs the lambda and passes only if it throws that exception type.

**Check:** `.\mvnw.cmd test -Dtest=StubPaymentProviderTest` → 4 green.

---

## Stage 3 — Executor: unknown outcome → reconciliation

### 3.1 `payment/application/PaymentExecutionServiceImpl.java`

In `execute`, wrap the provider call:

```java
    PaymentProviderResult result;
    try {
      result =
          paymentProvider.charge(
              charge.paymentId(),
              charge.amount(),
              charge.paymentMethod(),
              idempotencyKey(charge.paymentId()));
    } catch (ProviderOutcomeUnknownException e) {
      // The charge MAY have happened. Never retry the charge: hand over to reconciliation.
      log.warn("Payment {}: provider outcome unknown ({})", charge.paymentId(), e.getMessage());
      transactionTemplate.executeWithoutResult(
          status -> requireReconciliation(command, e.getMessage()));
      return;
    }
    transactionTemplate.executeWithoutResult(status -> finish(command, result));
```

Add the method next to `finish` (same shape: inbox check, guarded transition, outbox, inbox row):

```java
  private void requireReconciliation(ExecutePaymentCommand command, String reason) {
    if (consumedEventRepository.exists(CONSUMER_NAME, command.eventId())) {
      return;
    }
    PaymentEntity payment = loadPayment(command.paymentId());
    if (payment.getPaymentStatus() == PaymentStatus.PROCESSING) {
      payment.requireReconciliation(reason);
      paymentStatusChangedOutbox.append(payment, PaymentStatus.PROCESSING);
      log.info("Payment {} -> {}", payment.getId(), payment.getPaymentStatus());
    }
    consumedEventRepository.save(ConsumedEventEntity.of(CONSUMER_NAME, command.eventId()));
  }
```

Why the inbox row here too: the Kafka record is **done** (acked). If it is redelivered, the executor
must skip it, and `RECONCILIATION_REQUIRED` falls into the `default` branch of `startProcessing`
(no charge) anyway: two layers of protection.

Only `ProviderOutcomeUnknownException` is caught. Any other exception still propagates: Kafka
retries it, then (Stage 5) it goes to the DLT and the payment stays `PROCESSING`, which the job
picks up after 5 minutes (Stage 4).

### 3.2 Test: add to `src/test/.../application/PaymentExecutionServiceImplTest.java`

```java
  @Test
  void unknownProviderOutcomeMovesPaymentToReconciliation() {
    PaymentEntity payment = payment(PaymentStatus.PENDING);
    when(paymentRepository.findById(99L)).thenReturn(Optional.of(payment));
    when(paymentProvider.charge(any(), any(), any(), any()))
        .thenThrow(new ProviderOutcomeUnknownException("timeout"));

    paymentExecutionService.execute(command);

    assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.RECONCILIATION_REQUIRED);
    assertThat(payment.getOrder().getOrderStatus()).isEqualTo(OrderStatus.PAYMENT_PROCESSING);
    assertThat(payment.getFailureReason()).isEqualTo("timeout");
    verify(paymentProvider, times(1)).charge(any(), any(), any(), any()); // never retried
    verify(paymentStatusChangedOutbox).append(payment, PaymentStatus.PROCESSING);
    verify(consumedEventRepository).save(any(ConsumedEventEntity.class));
  }
```

`thenThrow(...)` is the opposite of `thenReturn`: the mock throws when called.

**Check:** `.\mvnw.cmd test -Dtest=PaymentExecutionServiceImplTest` → 6 green.

---

## Stage 4 — The reconciliation job

### 4.1 Query: `payment/application/PaymentRepository.java`

```java
  /**
   * Payments that need reconciliation: RECONCILIATION_REQUIRED last touched before {@code
   * retryBefore}, or PROCESSING last touched before {@code stuckBefore}. Oldest first.
   */
  List<Long> findIdsDueForReconciliation(
      LocalDateTime retryBefore, LocalDateTime stuckBefore, int limit);
```

### 4.2 `payment/infrastructure/repository/JpaDataPaymentRepository.java`

```java
  @Query(
      """
      select payment.id from PaymentEntity payment
      where (payment.paymentStatus = :reconciliationRequired and payment.updatedAt < :retryBefore)
         or (payment.paymentStatus = :processing and payment.updatedAt < :stuckBefore)
      order by payment.updatedAt
      """)
  List<Long> findIdsDueForReconciliation(
      @Param("reconciliationRequired") PaymentStatus reconciliationRequired,
      @Param("retryBefore") LocalDateTime retryBefore,
      @Param("processing") PaymentStatus processing,
      @Param("stuckBefore") LocalDateTime stuckBefore,
      Pageable pageable);
```

Imports: `PaymentStatus`, `java.time.LocalDateTime`, `org.springframework.data.domain.Pageable`.

This is **JPQL**, not SQL: it names the entity (`PaymentEntity`) and fields (`paymentStatus`,
`updatedAt`), not tables and columns. The enum is passed as a parameter; Hibernate converts it to the
`VARCHAR` value. It returns only **ids**: each payment is then loaded again inside its own
transaction.

### 4.3 `payment/infrastructure/repository/JpaPaymentRepository.java`

```java
  @Override
  public List<Long> findIdsDueForReconciliation(
      LocalDateTime retryBefore, LocalDateTime stuckBefore, int limit) {
    return jpaDataPaymentRepository.findIdsDueForReconciliation(
        PaymentStatus.RECONCILIATION_REQUIRED,
        retryBefore,
        PaymentStatus.PROCESSING,
        stuckBefore,
        PageRequest.of(0, limit));
  }
```

Imports: `PaymentStatus`, `java.time.LocalDateTime`, `org.springframework.data.domain.PageRequest`.
`PageRequest.of(0, limit)` = "first page of `limit` rows" → SQL `LIMIT`.

### 4.4 New `payment/application/PaymentReconciliationService.java`

```java
package com.example.payment.payment.application;

import java.util.List;

public interface PaymentReconciliationService {
  /** Ids of payments whose outcome must be looked up at the provider, oldest first. */
  List<Long> findDuePaymentIds(int limit);

  /** Asks the provider what happened to one payment and records the answer. Never charges. */
  void reconcile(Long paymentId);
}
```

### 4.5 New `payment/application/PaymentReconciliationServiceImpl.java`

```java
package com.example.payment.payment.application;

import com.example.payment.payment.domain.PaymentEntity;
import com.example.payment.payment.domain.PaymentStatus;
import com.example.payment.shared.application.ResourceNotFoundException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
public class PaymentReconciliationServiceImpl implements PaymentReconciliationService {
  static final String NOT_FOUND_AT_PROVIDER = "Provider has no record of this charge";

  private final PaymentRepository paymentRepository;
  private final PaymentProvider paymentProvider;
  private final PaymentStatusChangedOutbox paymentStatusChangedOutbox;
  private final TransactionTemplate transactionTemplate;
  private final Duration retryAfter;
  private final Duration stuckAfter;

  public PaymentReconciliationServiceImpl(
      PaymentRepository paymentRepository,
      PaymentProvider paymentProvider,
      PaymentStatusChangedOutbox paymentStatusChangedOutbox,
      TransactionTemplate transactionTemplate,
      @Value("${payment.reconciliation.retry-after:30s}") Duration retryAfter,
      @Value("${payment.reconciliation.stuck-after:5m}") Duration stuckAfter) {
    this.paymentRepository = paymentRepository;
    this.paymentProvider = paymentProvider;
    this.paymentStatusChangedOutbox = paymentStatusChangedOutbox;
    this.transactionTemplate = transactionTemplate;
    this.retryAfter = retryAfter;
    this.stuckAfter = stuckAfter;
  }

  @Override
  public List<Long> findDuePaymentIds(int limit) {
    LocalDateTime now = LocalDateTime.now();
    return paymentRepository.findIdsDueForReconciliation(
        now.minus(retryAfter), now.minus(stuckAfter), limit);
  }

  @Override
  public void reconcile(Long paymentId) {
    // TX 1 — a payment stuck in PROCESSING is moved to RECONCILIATION_REQUIRED (visible, audited).
    Boolean due = transactionTemplate.execute(status -> markForReconciliation(paymentId));
    if (!Boolean.TRUE.equals(due)) {
      return;
    }

    // No transaction: ask the provider. A lookup, NEVER a charge.
    Optional<PaymentProviderResult> found =
        paymentProvider.findByIdempotencyKey(PaymentExecutionServiceImpl.idempotencyKey(paymentId));

    // TX 2 — record the provider's answer + status event, atomically.
    transactionTemplate.executeWithoutResult(status -> resolve(paymentId, found));
  }

  private boolean markForReconciliation(Long paymentId) {
    PaymentEntity payment = loadPayment(paymentId);
    switch (payment.getPaymentStatus()) {
      case PROCESSING -> {
        payment.requireReconciliation("Stuck in PROCESSING");
        paymentStatusChangedOutbox.append(payment, PaymentStatus.PROCESSING);
        log.warn("Payment {} stuck in PROCESSING -> RECONCILIATION_REQUIRED", paymentId);
        return true;
      }
      case RECONCILIATION_REQUIRED -> {
        return true;
      }
      default -> {
        return false; // already resolved (by the executor or another run)
      }
    }
  }

  private void resolve(Long paymentId, Optional<PaymentProviderResult> found) {
    PaymentEntity payment = loadPayment(paymentId);
    if (payment.getPaymentStatus() != PaymentStatus.RECONCILIATION_REQUIRED) {
      return; // resolved meanwhile
    }

    if (found.isEmpty()) {
      // The provider never received the charge, so the customer was not charged.
      payment.fail(null, NOT_FOUND_AT_PROVIDER);
    } else {
      PaymentProviderResult result = found.get();
      switch (result.outcome()) {
        case SUCCEEDED -> payment.complete(result.providerReference());
        case DECLINED -> payment.fail(result.providerReference(), result.failureReason());
      }
    }
    paymentStatusChangedOutbox.append(payment, PaymentStatus.RECONCILIATION_REQUIRED);
    log.info("Payment {} reconciled -> {}", paymentId, payment.getPaymentStatus());
  }

  private PaymentEntity loadPayment(Long paymentId) {
    return paymentRepository
        .findById(paymentId)
        .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
  }
}
```

Points to understand:

- **Same 2-transaction shape as the executor**, but the middle step is `findByIdempotencyKey`, never
  `charge`.
- **Explicit constructor** instead of `@RequiredArgsConstructor`: Lombok cannot put `@Value` on
  constructor parameters for us. Explicit constructor = easy to call from the unit test with any
  durations.
- **"Not found" → FAILED is safe only because of `stuck-after`.** If the job looked at a payment whose
  charge is still on the wire, the provider might not know it *yet*. `stuck-after=5m` is far above any
  normal charge (2 s here). Never lower it near the provider timeout.
- **Two app instances?** Both may pick the same payment. `@Version` makes the second commit fail with
  an optimistic lock error; the job logs it and the next run sees it resolved.
- **Retry pace:** a `RECONCILIATION_REQUIRED` row whose lookup keeps failing is retried every run
  (its `updated_at` does not change). Fine for now; Task 6 adds an attempt counter, back-off and an
  alert.

### 4.6 New `payment/infrastructure/scheduling/PaymentReconciliationJob.java`

```java
package com.example.payment.payment.infrastructure.scheduling;

import com.example.payment.payment.application.PaymentReconciliationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically resolves payments whose provider outcome is unknown. */
@Slf4j
@Component
@ConditionalOnProperty(name = "payment.reconciliation.enabled", havingValue = "true")
public class PaymentReconciliationJob {
  private final PaymentReconciliationService reconciliationService;
  private final int batchSize;

  public PaymentReconciliationJob(
      PaymentReconciliationService reconciliationService,
      @Value("${payment.reconciliation.batch-size:20}") int batchSize) {
    this.reconciliationService = reconciliationService;
    this.batchSize = batchSize;
  }

  @Scheduled(fixedDelayString = "${payment.reconciliation.fixed-delay:10s}")
  public void run() {
    for (Long paymentId : reconciliationService.findDuePaymentIds(batchSize)) {
      try {
        reconciliationService.reconcile(paymentId);
      } catch (RuntimeException e) {
        // e.g. provider still down: leave it, the next run retries. One bad payment must not
        // block the others.
        log.warn("Reconciliation of payment {} failed, will retry: {}", paymentId, e.toString());
      }
    }
  }
}
```

`@EnableScheduling` is already on `PaymentApplication` (the outbox publisher uses it).
`fixedDelay` = wait 10 s **after a run ends** before the next one, so runs never overlap.
The job is thin on purpose: the logic lives in the service, which is unit-tested without Spring.

### 4.7 Test: new `src/test/.../application/PaymentReconciliationServiceImplTest.java`

```java
package com.example.payment.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.payment.order.domain.OrderEntity;
import com.example.payment.order.domain.OrderStatus;
import com.example.payment.payment.domain.PaymentEntity;
import com.example.payment.payment.domain.PaymentMethod;
import com.example.payment.payment.domain.PaymentStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class PaymentReconciliationServiceImplTest {
  @Mock private PaymentRepository paymentRepository;
  @Mock private PaymentProvider paymentProvider;
  @Mock private PaymentStatusChangedOutbox paymentStatusChangedOutbox;
  @Mock private PlatformTransactionManager transactionManager;

  private PaymentReconciliationServiceImpl reconciliationService;

  @BeforeEach
  void setUp() {
    reconciliationService =
        new PaymentReconciliationServiceImpl(
            paymentRepository,
            paymentProvider,
            paymentStatusChangedOutbox,
            new TransactionTemplate(transactionManager),
            Duration.ofSeconds(30),
            Duration.ofMinutes(5));
  }

  @Test
  void providerSaysSucceededCompletesPayment() {
    PaymentEntity payment = payment(PaymentStatus.RECONCILIATION_REQUIRED);
    when(paymentRepository.findById(99L)).thenReturn(Optional.of(payment));
    when(paymentProvider.findByIdempotencyKey("payment-99"))
        .thenReturn(Optional.of(PaymentProviderResult.succeeded("STUB-1")));

    reconciliationService.reconcile(99L);

    assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(payment.getOrder().getOrderStatus()).isEqualTo(OrderStatus.PAID);
    assertThat(payment.getProviderReference()).isEqualTo("STUB-1");
    assertThat(payment.getFailureReason()).isNull();
    verify(paymentStatusChangedOutbox).append(payment, PaymentStatus.RECONCILIATION_REQUIRED);
    verify(paymentProvider, never()).charge(any(), any(), any(), any()); // lookup only
  }

  @Test
  void providerHasNoRecordFailsPayment() {
    PaymentEntity payment = payment(PaymentStatus.RECONCILIATION_REQUIRED);
    when(paymentRepository.findById(99L)).thenReturn(Optional.of(payment));
    when(paymentProvider.findByIdempotencyKey("payment-99")).thenReturn(Optional.empty());

    reconciliationService.reconcile(99L);

    assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.FAILED);
    assertThat(payment.getOrder().getOrderStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
    assertThat(payment.getFailureReason())
        .isEqualTo(PaymentReconciliationServiceImpl.NOT_FOUND_AT_PROVIDER);
  }

  @Test
  void stuckProcessingPaymentIsMarkedThenResolved() {
    PaymentEntity payment = payment(PaymentStatus.PROCESSING);
    when(paymentRepository.findById(99L)).thenReturn(Optional.of(payment));
    when(paymentProvider.findByIdempotencyKey("payment-99"))
        .thenReturn(Optional.of(PaymentProviderResult.succeeded("STUB-1")));

    reconciliationService.reconcile(99L);

    assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.COMPLETED);
    verify(paymentStatusChangedOutbox).append(payment, PaymentStatus.PROCESSING);
    verify(paymentStatusChangedOutbox).append(payment, PaymentStatus.RECONCILIATION_REQUIRED);
  }

  @Test
  void providerLookupErrorLeavesPaymentForNextRun() {
    PaymentEntity payment = payment(PaymentStatus.RECONCILIATION_REQUIRED);
    when(paymentRepository.findById(99L)).thenReturn(Optional.of(payment));
    when(paymentProvider.findByIdempotencyKey("payment-99"))
        .thenThrow(new IllegalStateException("provider down"));

    assertThatThrownBy(() -> reconciliationService.reconcile(99L))
        .isInstanceOf(IllegalStateException.class);

    assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.RECONCILIATION_REQUIRED);
    verifyNoInteractions(paymentStatusChangedOutbox);
  }

  @Test
  void alreadyResolvedPaymentIsLeftAlone() {
    when(paymentRepository.findById(99L)).thenReturn(Optional.of(payment(PaymentStatus.COMPLETED)));

    reconciliationService.reconcile(99L);

    verifyNoInteractions(paymentProvider, paymentStatusChangedOutbox);
  }

  private static PaymentEntity payment(PaymentStatus status) {
    OrderEntity order = new OrderEntity();
    ReflectionTestUtils.setField(order, "id", 42L);
    order.setOrderStatus(OrderStatus.PAYMENT_PROCESSING);
    order.setOrderAmount(new BigDecimal("120000.00"));

    PaymentEntity payment = new PaymentEntity();
    ReflectionTestUtils.setField(payment, "id", 99L);
    payment.setOrder(order);
    payment.setPaymentMethod(PaymentMethod.EWALLET);
    payment.setPaymentStatus(status);
    return payment;
  }
}
```

**Check:** `.\mvnw.cmd test -Dtest=PaymentReconciliationServiceImplTest` → 5 green.

---

## Stage 5 — Dead letter topic + config

### 5.1 `payment/infrastructure/messaging/PaymentExecutorKafkaConfiguration.java` (replace)

```java
package com.example.payment.payment.infrastructure.messaging;

import com.example.payment.shared.application.ResourceNotFoundException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import tools.jackson.core.JacksonException;

@Configuration
@ConditionalOnProperty(name = "payment.executor.enabled", havingValue = "true")
public class PaymentExecutorKafkaConfiguration {
  static final String DLT_SUFFIX = ".dlt";

  /** Spring Boot plugs this bean into the default listener container factory. */
  @Bean
  DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
    // After the retries, park the record on "<topic>.dlt" (same partition) instead of dropping it.
    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(
            kafkaTemplate,
            (record, exception) ->
                new TopicPartition(record.topic() + DLT_SUFFIX, record.partition()));

    // Transient errors: 1s, 2s, 4s, 8s (4 retries), then the DLT.
    ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(4);
    backOff.setInitialInterval(1_000L);
    backOff.setMultiplier(2.0);
    backOff.setMaxInterval(10_000L);

    DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
    // Can never succeed: straight to the DLT, no retries.
    errorHandler.addNotRetryableExceptions(JacksonException.class, ResourceNotFoundException.class);
    // We ack manually (manual_immediate): also commit the offset of a record sent to the DLT,
    // otherwise it is read again after a restart.
    errorHandler.setCommitRecovered(true);
    return errorHandler;
  }
}
```

- **DLT = a parking lot**, not a trash bin. The record keeps its key and value; Spring adds headers
  (`kafka_dlt-exception-fqcn`, `kafka_dlt-exception-message`, original topic/partition/offset).
  Someone can inspect it, fix the cause and replay it on purpose.
- **Exponential back-off** (1 s → 2 s → 4 s → 8 s) gives a struggling DB time to recover instead of
  hammering it every second.
- **`setCommitRecovered(true)`** is easy to forget: with manual ack, without it the DLT'd record is
  read and DLT'd again after every restart.

### 5.2 `src/main/resources/application.properties`

Below the `payment.provider.stub.decline-above` line:

```properties
# This exact amount is charged but the response is "lost" (simulated timeout) -> reconciliation.
payment.provider.stub.timeout-amount=${PAYMENT_PROVIDER_STUB_TIMEOUT_AMOUNT:7777000}

# Reconciliation: resolve payments whose provider outcome is unknown, by LOOKING UP (never charging).
payment.reconciliation.enabled=${PAYMENT_RECONCILIATION_ENABLED:false}
payment.reconciliation.fixed-delay=10s
# Wait this long after the payment was last touched before asking the provider (again).
payment.reconciliation.retry-after=30s
# PROCESSING longer than this = the executor crashed or gave up. Must be far above a normal charge.
payment.reconciliation.stuck-after=5m
payment.reconciliation.batch-size=20
```

### 5.3 `docker-compose.yml`

1. `payment-local.environment`: add `PAYMENT_RECONCILIATION_ENABLED: "true"`.
2. `kafka-init.command`: add the DLT topic to the loop:

```yaml
        for topic in payment.requested.v1 payment.requested.v1.dlt payment.status-changed.v1; do
```

The DLT needs **at least as many partitions** as the source topic (3), because the recoverer writes
to the same partition number.

### 5.4 `src/test/java/com/example/payment/KafkaTestcontainersConfiguration.java`

Add a third topic bean:

```java
  @Bean
  NewTopic paymentRequestedDeadLetterTopic() {
    return TopicBuilder.name("payment.requested.v1.dlt").partitions(3).replicas(1).build();
  }
```

(While there: remove the two stray `;` after the method bodies.)

**Check:** `.\mvnw.cmd test` → 26 minus Stage 6's 2 = **24 green**.

---

## Stage 6 — Integration test: both failure paths, end to end

New `src/test/java/com/example/payment/payment/infrastructure/messaging/PaymentFailureHandlingIntegrationTest.java`:

```java
package com.example.payment.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.example.payment.KafkaTestcontainersConfiguration;
import com.example.payment.TestcontainersConfiguration;
import com.example.payment.payment.application.PaymentProvider;
import com.example.payment.payment.application.PaymentService;
import com.example.payment.payment.domain.PaymentMethod;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(
    properties = {
      "payment.outbox.publisher.enabled=true",
      "payment.executor.enabled=true",
      "payment.provider.stub.latency=0s",
      "payment.provider.stub.timeout-amount=7777000",
      "payment.reconciliation.enabled=true",
      "payment.reconciliation.fixed-delay=500ms",
      "payment.reconciliation.retry-after=0s"
    })
@Import({TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class})
class PaymentFailureHandlingIntegrationTest {
  private static final String REQUESTED_TOPIC = "payment.requested.v1";
  private static final String DEAD_LETTER_TOPIC = "payment.requested.v1.dlt";
  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  @Autowired private PaymentService paymentService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private KafkaTemplate<String, String> kafkaTemplate;
  @Autowired private ConsumerFactory<String, String> consumerFactory;

  @MockitoSpyBean private PaymentProvider paymentProvider;

  @Test
  void lostProviderResponseIsResolvedByReconciliationWithoutSecondCharge() {
    Long paymentId = createPayment(new BigDecimal("7777000.00")); // stub: charged, then "timeout"

    // Executor -> RECONCILIATION_REQUIRED -> reconciliation job asks provider -> COMPLETED.
    await().atMost(TIMEOUT).until(() -> "COMPLETED".equals(paymentStatus(paymentId)));

    assertThat(orderStatusOfPayment(paymentId)).isEqualTo("PAID");
    verify(paymentProvider, times(1)).charge(eq(paymentId), any(), any(), any()); // ONE charge
    verify(paymentProvider, atLeastOnce()).findByIdempotencyKey("payment-" + paymentId);
    assertThat(statusEvents(paymentId))
        .containsExactly("PROCESSING", "RECONCILIATION_REQUIRED", "COMPLETED");
  }

  @Test
  void poisonMessageGoesToDeadLetterTopic() throws Exception {
    kafkaTemplate.send(REQUESTED_TOPIC, "poison", "this is not json").get();

    try (Consumer<String, String> consumer =
        consumerFactory.createConsumer("dlt-check-" + UUID.randomUUID(), null)) {
      consumer.subscribe(List.of(DEAD_LETTER_TOPIC));
      ConsumerRecord<String, String> parked =
          KafkaTestUtils.getSingleRecord(consumer, DEAD_LETTER_TOPIC, TIMEOUT);

      assertThat(parked.key()).isEqualTo("poison");
      assertThat(parked.value()).isEqualTo("this is not json");
    }
  }

  // ---------- test data ----------

  /** Inserts customer, product and a PENDING order with SQL, then creates the payment for real. */
  private Long createPayment(BigDecimal amount) {
    String unique = UUID.randomUUID().toString();
    Long customerId =
        jdbcTemplate.queryForObject(
            """
            insert into users (first_name, last_name, email, phone_number, created_at, updated_at)
            values ('Test', 'User', ?, '0900000000', now(), now()) returning id
            """,
            Long.class,
            unique + "@example.com");
    Long productId =
        jdbcTemplate.queryForObject(
            """
            insert into products (product_name, product_description, unit_price, created_at, updated_at)
            values ('Coffee', 'Beans', ?, now(), now()) returning id
            """,
            Long.class,
            amount);
    Long orderId =
        jdbcTemplate.queryForObject(
            """
            insert into orders (order_code, order_status, order_amount, customer_id, created_at, updated_at)
            values (?, 'PENDING', ?, ?, now(), now()) returning id
            """,
            Long.class,
            "ORD-" + unique,
            amount,
            customerId);
    jdbcTemplate.update(
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
    return jdbcTemplate.queryForObject(
        "select payment_status from payments where id = ?", String.class, paymentId);
  }

  private String orderStatusOfPayment(Long paymentId) {
    return jdbcTemplate.queryForObject(
        "select o.order_status from orders o join payments p on p.order_id = o.id where p.id = ?",
        String.class,
        paymentId);
  }

  /** The "to" status of every payment.status-changed.v1 event for this payment, in order. */
  private List<String> statusEvents(Long paymentId) {
    return jdbcTemplate.queryForList(
        """
        select payload -> 'payload' ->> 'nextStatus' from outbox_events
        where event_type = 'payment.status-changed.v1' and aggregate_id = ?
        order by id
        """,
        String.class,
        paymentId);
  }
}
```

Points to understand:

- **`7777000.00`** = the stub's timeout amount: the stub charges, stores the result, then throws.
  The test proves the worst case ends `COMPLETED` with **exactly one** `charge` call and at least one
  `findByIdempotencyKey` call.
- **`retry-after=0s`, `fixed-delay=500ms`**: in the test the job reacts within half a second instead
  of 30 s.
- **`statusEvents`** reads the outbox rows' JSON with PostgreSQL operators: `->` gets a JSON field,
  `->>` gets it as text. `nextStatus` is the field name in your `PaymentStatusChangedEvent`.
- **`createPayment` is a copy** of the helper in `PaymentExecutorKafkaIntegrationTest`. Fine for two
  classes; with a third, move it to a shared `PaymentTestData` class.
- **DLT test**: `consumerFactory.createConsumer(groupId, null)` builds a plain Kafka consumer from
  Boot's settings (with `auto-offset-reset=earliest`). `KafkaTestUtils.getSingleRecord` polls until
  exactly one record arrives or the timeout passes.

**Check:**

```powershell
.\mvnw.cmd spotless:apply
.\mvnw.cmd test -Dtest=PaymentFailureHandlingIntegrationTest   # 2 green
.\mvnw.cmd clean test                                           # 26 green
```

---

## Demo on Docker

```powershell
cd C:\WorkSpace\Code\Payment_Project\payment
docker compose down -v
docker compose up --build -d
docker compose logs -f payment-local     # second terminal
```

```powershell
$API = "http://localhost:8081"
function Send-Post($path, $body) { Invoke-RestMethod -Method Post -Uri "$API$path" -ContentType "application/json" -Body ($body | ConvertTo-Json -Depth 5) }

Send-Post "/customers" @{ firstName="An"; lastName="Nguyen"; email="an@example.com"; phoneNumber="0900000001" }
Send-Post "/product"   @{ productName="Lost response"; productDescription="Demo"; unitPrice=7777000 }
Send-Post "/orders"    @{ orderCode="ORD-T"; customerId=1; items=@(@{ productId=1; quantity=1 }) }
Send-Post "/payments"  @{ orderId=1; method="CARD" }

# PROCESSING (~2 s) -> RECONCILIATION_REQUIRED -> about 30-40 s later -> COMPLETED
1..12 | ForEach-Object { "$(Get-Date -Format HH:mm:ss) " + ((Invoke-RestMethod "$API/payments/1") | ConvertTo-Json -Compress); Start-Sleep 5 }
```

Logs to expect: `Stub provider: charged key payment-1 but simulating a timeout` →
`provider outcome unknown` → `Payment 1 -> RECONCILIATION_REQUIRED` → (≈30 s) →
`Payment 1 reconciled -> COMPLETED`.

Poison message → DLT:

```powershell
docker compose exec kafka bash -c "echo 'oops:not json' | /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:19092 --topic payment.requested.v1 --property parse.key=true --property key.separator=:"
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:19092 --topic payment.requested.v1.dlt --from-beginning --timeout-ms 5000 --property print.key=true --property print.headers=true
```

Expected: `oops  not json` with headers `kafka_dlt-exception-fqcn:...JacksonException...`.

(The Docker demo commands were not run when this guide was written; the same two paths are proven by
`PaymentFailureHandlingIntegrationTest`. The "stuck in PROCESSING" path is covered by the unit test
`stuckProcessingPaymentIsMarkedThenResolved`.)

---

## Done checklist

- [ ] Stage checks 1-6 green; final `clean test` = 26
- [ ] Demo: 7,777,000 VND payment ends `COMPLETED` via `RECONCILIATION_REQUIRED`, one charge in logs
- [ ] Demo: poison message visible on `payment.requested.v1.dlt` with exception headers
- [ ] Roadmap Task 5 status → implemented (DLT replay tool and metrics stay for Task 6)

## Not in this task

- **Task 6:** attempt counter + back-off + alert for payments stuck in `RECONCILIATION_REQUIRED`;
  an authenticated, audited **DLT replay** endpoint; metrics (DLT count, reconciliation age).
- A real provider adapter (HTTP client with connect/read timeouts mapped to the right exception).
