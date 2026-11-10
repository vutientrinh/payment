# Kafka Transactional Outbox Roadmap

## Goal

Process a payment asynchronously without making payment creation dependent on Kafka availability and without losing events after a successful database commit.

The database remains the source of truth. Kafka provides at-least-once delivery; every downstream handler must therefore be idempotent.

## Target Flow

```text
POST /payments
  -> one database transaction
       -> create Payment(PENDING)
       -> change Order to PAYMENT_PROCESSING
       -> insert OutboxEvent(payment.requested.v1, PENDING)
  -> commit

Outbox publisher
  -> claim ready outbox rows
  -> publish to Kafka with key = paymentId
  -> mark row PUBLISHED only after Kafka acknowledges

Payment executor consumer
  -> deduplicate event
  -> transition payment to PROCESSING
  -> call provider with provider idempotency key = paymentId
  -> one database transaction
       -> store provider result
       -> transition Payment to terminal/reconciliation state
       -> insert payment.status-changed.v1 into outbox
       -> record event as consumed
  -> commit consumer offset after database commit
```

## Non-Negotiable Invariants

1. A committed payment creation has exactly one committed `payment.requested.v1` outbox record.
2. A rolled-back payment creation has no persisted payment, order transition, or outbox record.
3. An outbox event is marked `PUBLISHED` only after Kafka returns a successful broker acknowledgement.
4. A duplicate Kafka delivery must not trigger a second provider charge or duplicate business side effect.
5. A provider timeout is an unknown result, not a payment failure. Reconcile by provider idempotency key before any retry.
6. Events for the same payment use `paymentId` as Kafka key to preserve per-payment partition ordering.
7. No card data, payment token, or raw provider payload containing PII is written to Kafka logs or application logs.

## Work Plan

### Task 0 — Establish Baseline and Migration Strategy

**Status:** pending.

**Why first:** the application currently relies on JPA entities while the initial outbox migration is `V1`. Before any shared database deployment, establish whether the existing domain schema is created by Hibernate or has an external migration history. Applying `V1` to a database that already has domain tables can leave schema ownership inconsistent.

**Work:**

- Decide and document schema ownership: Flyway only for production; disable `ddl-auto` schema changes in production.
- Create/baseline Flyway migrations for existing `customers`, `products`, `orders`, `order_items`, and `payments` tables before deploying outbox.
- Rename/resequence the outbox migration if an existing Flyway history already has version `1`.
- Configure local/test PostgreSQL datasource and Flyway locations.
- Repair `PaymentApplicationTests` package/configuration so it loads `com.example.payment.PaymentApplication`.

**Acceptance criteria:** a fresh PostgreSQL database is built only by Flyway; `mvnw.cmd test` has a green application context test using an isolated test database.

### Task 1 — Persist Payment Requested Event Atomically

**Status:** implemented; requires Task 0 migration-baseline review before production deployment.

**Implemented artifacts:**

- `outbox_events` table migration with `PENDING`, retry, publication timestamp, JSONB payload, event UUID, and publishable-row index.
- `OutboxEventEntity` plus application and JPA repository adapters.
- `PaymentRequestedEvent` contract.
- `PaymentServiceImpl#createPayment` stores `Payment`, moves the order to `PAYMENT_PROCESSING`, then stores `payment.requested.v1` in the same Spring transaction.
- Unit test validates event type, topic, key, aggregate ID, and serialized payload.

**Follow-up hardening:**

- Replace the application-level `existsByOrderId` check with a database-constraint-aware conflict path. The unique payment-to-order constraint remains the final protection under concurrent requests.
- Add an API idempotency key and a unique constraint, e.g. `(merchant_id, idempotency_key)`, before accepting public payment creation requests.
- Add optimistic locking (`@Version`) to `PaymentEntity` and likely `OrderEntity` before concurrent executor/reconciliation updates.
- Define the canonical money representation. `OrderEntity.currency` is currently `BigDecimal`; use a currency code type/string and ensure amount/scale rules are explicit.

**Acceptance criteria:** payment, order status change, and outbox row commit or roll back together in a PostgreSQL integration test.

### Task 2 — Build the Outbox Publisher

**Status:** implemented; PostgreSQL/Kafka integration tests remain in Task 7.

**Recommended implementation:** polling publisher for the initial rollout. Debezium CDC can be evaluated later if polling throughput or database load becomes a demonstrated bottleneck.

**Implemented:**

- `OutboxEventRepository.claimPendingBatch(...)` uses PostgreSQL `FOR UPDATE SKIP LOCKED` inside a short transaction.
- `IN_PROGRESS` lease status, lease owner, and lease expiration prevent concurrent publishers from owning a row at the same time; expired leases are reclaimable.
- Scheduled publisher has bounded batch size and batches per run; it is disabled by default and enabled only through `payment.outbox.publisher.enabled=true`.
- `KafkaTemplate` publishing waits for broker acknowledgement with an explicit timeout. Producer configuration enables `acks=all` and idempotence.
- Acknowledged records are marked `PUBLISHED` only while the publisher still owns the lease.
- Failures are recorded with capped exponential backoff plus jitter; retries release the lease and terminal failures become `FAILED` after the configured maximum attempt count.
- Kafka records include `eventId` and `eventType` headers so the future consumer inbox can deduplicate delivery.
- Invalid timing configuration, including a lease shorter than Kafka send timeout, fails application binding validation.

**Kafka producer settings:**

```properties
spring.kafka.producer.properties.enable.idempotence=true
spring.kafka.producer.acks=all
spring.kafka.producer.properties.max.in.flight.requests.per.connection=5
```

**Acceptance criteria:**

- Two publisher instances can run concurrently without publishing the same claimed row at the same time.
- Broker failure retains a retryable row; successful acknowledgement eventually marks it `PUBLISHED`.
- A process crash between broker acknowledgement and DB update may publish a duplicate, which is expected and handled by consumer idempotency.

### Task 3 — Define and Govern Event Contracts

**Status:** pending.

**Work:**

- Define a common versioned event envelope: `eventId`, `eventType`, `aggregateId`, `occurredAt`, `traceId`, `payload`.
- Keep `payment.requested.v1` immutable after release. Add a new event version for incompatible changes.
- Document required payload fields and nullable/optional fields.
- Add schema/contract tests for producer serialization and consumer deserialization.
- Define topic ownership, retention, partition count, ACLs, and compatibility policy.

**Initial topics:**

| Topic | Producer | Consumer | Key |
| --- | --- | --- | --- |
| `payment.requested.v1` | payment outbox publisher | payment executor | `paymentId` |
| `payment.status-changed.v1` | executor outbox publisher | notification, ledger, callback | `paymentId` |
| `payment.reconciliation-requested.v1` | executor outbox publisher | reconciliation worker | `paymentId` |
| `payment.dlt.v1` | consumer error handler | operations/replay worker | original event key |

**Acceptance criteria:** consumers can reject invalid or unknown incompatible payloads safely and route them to DLT without silently corrupting payment state.

### Task 4 — Implement Idempotent Payment Executor Consumer

**Status:** pending.

**Work:**

- Add `consumed_events` (inbox) table with primary key `(consumer_name, event_id)`.
- In one DB transaction, insert consumed-event record and claim payment state with optimistic lock or guarded update.
- Permit only valid state transitions. Terminal states are immutable except an explicitly designed refund workflow.
- Transition `PENDING -> PROCESSING` before provider handling; ensure duplicate and stale events are no-ops.
- Call provider using stable provider idempotency key derived from immutable `paymentId`.
- On definitive provider result, persist provider reference/result, update payment/order status, and append `payment.status-changed.v1` outbox event in one transaction.
- Acknowledge Kafka only after the DB transaction commits.

**Important limitation:** a database transaction cannot atomically include an external provider API call. The provider idempotency key and reconciliation process are what make retry safe.

**Acceptance criteria:** replaying the exact same Kafka record does not create a new provider charge, duplicate payment status event, or duplicate notification.

### Task 5 — Reconciliation and Failure Handling

**Status:** pending.

**Work:**

- Add `RECONCILIATION_REQUIRED`/`UNKNOWN` payment status, or an equivalent explicit processing-result state.
- Classify provider outcomes: definitive success, definitive failure, transient failure, and unknown timeout/connection loss.
- For unknown outcomes, enqueue `payment.reconciliation-requested.v1`; query provider by provider idempotency key instead of retrying a charge.
- Build a bounded retry policy with retry classifications; prevent retry storms with backoff and concurrency limits.
- Configure Kafka listener retries for transient processing errors and DLT for poison/invalid events.
- Create an authenticated, audited replay mechanism for failed outbox/DLT events; never replay blindly.

**Acceptance criteria:** simulated provider timeout never causes an immediate second charge attempt; reconciliation resolves state from provider evidence.

### Task 6 — Security, Configuration, and Operations

**Status:** pending.

**Work:**

- Configure Kafka brokers, TLS/SASL credentials, client IDs, timeouts, topic names, batch/retry limits via environment-specific config; keep secrets outside source control.
- Apply Kafka ACLs: publisher write-only to owned topics; executor consumer group read-only to required topics.
- Propagate correlation/trace ID from HTTP request into event envelope and consumer logs.
- Add structured logs with `paymentId`, `eventId`, `providerReference`, and `traceId`; mask sensitive values.
- Add metrics: pending count, oldest outbox age, published/failed/retried totals, consumer lag, DLT count, reconciliation age, provider error/timeout rate.
- Add health indicators that distinguish DB health, Kafka reachability, and outbox backlog; do not make a temporary Kafka outage fail normal DB-backed API writes.
- Add dashboards and alerts for growing pending outbox age, failed events, DLT, and stuck reconciliation.

**Acceptance criteria:** operations can identify a delayed payment, trace it across API/outbox/Kafka/consumer, and replay it under audit.

### Task 7 — Test Strategy

**Status:** pending beyond the existing unit test.

**Work:**

- Unit tests: event serialization, state transitions, retry classification/backoff, idempotency guards.
- PostgreSQL integration tests: payment plus outbox atomic commit/rollback; `SKIP LOCKED` claim behavior; concurrent payment creation for one order.
- Kafka integration tests (Testcontainers): publish acknowledgement, consumer duplicate delivery, retry/DLT behavior, partition key ordering for a payment.
- Provider adapter tests: deterministic failure, timeout, duplicate request with same idempotency key, reconciliation result.
- Contract tests for every published event version.
- Load test publisher batch size and consumer concurrency under realistic backlog.

**Minimum failure cases:**

1. DB commits while Kafka is unavailable.
2. Kafka acknowledges but publisher dies before outbox update.
3. Provider succeeds but DB transaction fails.
4. Consumer receives the same event repeatedly.
5. Two requests attempt a payment for one order concurrently.
6. Provider call times out with unknown remote outcome.

### Task 8 — Safe Rollout

**Status:** pending.

**Work:**

1. Deploy schema baseline and outbox schema first.
2. Deploy transactional outbox writes with publisher disabled; verify rows, index usage, and metrics.
3. Enable publisher to a non-production/shadow topic; validate contract and publication latency.
4. Enable executor for one non-critical payment method or allowlisted tenant behind a feature flag.
5. Enable downstream notification/ledger/callback consumers.
6. Scale gradually while monitoring lag, outbox age, DLT, provider errors, and reconciliation queue.
7. Publish runbooks for pause, replay, reconciliation, and incident response before broad rollout.

**Rollback policy:** stop consumers/publisher with feature flags first. Do not delete outbox rows. Pending events are recovery evidence and must remain replayable.

## Decision Record

### Polling Publisher vs Debezium CDC

| Option | Pros | Cons | Use when |
| --- | --- | --- | --- |
| Polling publisher | Minimal new infrastructure; direct retry/lease control; suitable for current application | DB polling/load; must implement locking and cleanup correctly | Initial implementation and moderate event volume |
| Debezium CDC outbox | Lower application polling; good scaling and change capture | Requires Kafka Connect, Debezium operations, connector monitoring, and schema governance | Existing CDC platform or proven high event volume |

**Recommendation:** start with a polling publisher. Revisit CDC only after measuring database polling overhead or throughput limits.

## Definition of Done

The Kafka payment workflow is complete only when:

- Database schema is Flyway-managed and validated on a fresh PostgreSQL database.
- Payment creation and outbox write are atomic.
- Publisher claim/publish/retry behavior is safe under multiple instances and crash recovery.
- Consumer processing is idempotent and payment-provider calls have stable idempotency keys.
- Unknown provider outcomes are reconciled, not retried as new charges.
- DLT/replay, metrics, logs, dashboards, alerts, and operational runbooks exist.
- Integration tests cover duplicate delivery, broker failure, provider failure, concurrent requests, and transaction rollback.
