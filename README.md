# Payment Service

A service for managing customers, products, orders, and payments. It stores data in PostgreSQL and uses the **transactional outbox** pattern to publish payment-creation events to Kafka without making `POST /payments` depend on Kafka availability.

> Current status: data-management APIs, payment creation, and the outbox publisher are implemented. The payment executor/consumer, API idempotency, and reconciliation are **not implemented**; this is therefore not yet a complete production payment flow.

## Technology stack

- Java 21, Spring Boot 4.1, Spring MVC, Spring Data JPA
- PostgreSQL and Hibernate
- Apache Kafka and Spring Kafka
- Maven Wrapper, Lombok, and Actuator
- Docker Compose for the local environment

## Source structure

```text
src/main/java/com/example/payment
├── customer/                         # Customer CRUD
│   ├── api/                          # HTTP controller, request/response DTOs
│   ├── application/                  # Use cases and repository ports
│   ├── domain/                       # UserEntity
│   └── infrastructure/repository/    # Spring Data JPA adapter
├── product/                          # Product CRUD, same structure as customer
├── order/                            # Orders and order items
│   ├── api/
│   ├── application/
│   ├── domain/
│   └── infrastructure/repository/
├── payment/                          # Payment lifecycle and payment-requested event
│   ├── api/
│   ├── application/
│   ├── domain/
│   └── infrastructure/repository/
└── shared/
    ├── api/                          # Shared exception mapping
    ├── application/                  # Conflict and not-found exceptions
    ├── domain/                       # BaseEntity (id, timestamps, soft delete)
    ├── messaging/                    # Versioned EventEnvelope<T>
    └── outbox/                       # Outbox domain, repository, and Kafka publisher

src/main/resources
└── application.properties            # Kafka/outbox defaults

docs/
└── kafka-transactional-outbox-roadmap.md
```

Each bounded area follows `api -> application -> domain <- infrastructure`: controllers use DTOs, application services contain use cases and depend on repository ports, and JPA adapters live in `infrastructure`.

## Current capabilities

### Customer

- Create, list, retrieve, update, and soft-delete customers.
- Emails are normalized with `trim().toLowerCase(Locale.ROOT)`.
- Duplicate emails return `409 Conflict`.

### Product

- Create, list, retrieve, update, and soft-delete products.
- `unitPrice` is required and must be positive.

### Order

- Create an order for one customer with at least one product item.
- Each order item snapshots `Product.unitPrice` when the order is created or updated; `orderAmount` is the total of `price * quantity`.
- New orders start in `PENDING` status.
- An order cannot be updated or deleted after a payment has been created; the API returns `409 Conflict`.

### Payment

- An order can have only one payment (enforced by both the one-to-one entity mapping and business validation).
- A payment can be created only for a `PENDING` order.
- Payment creation runs in **one Spring transaction** and:
  1. creates a `Payment` in `PENDING` status;
  2. moves the order to `PAYMENT_PROCESSING`;
  3. inserts a `payment.requested.v1` record into `outbox_events`.
- Payment status updates allow only these transitions:

```text
PENDING    -> PROCESSING | FAILED
PROCESSING -> COMPLETED  | FAILED
COMPLETED  -> REFUNDED
FAILED, REFUNDED -> no further transition
```

Order status follows payment status: `PENDING`/`PROCESSING` -> `PAYMENT_PROCESSING`, `COMPLETED` -> `PAID`, `FAILED` -> `PAYMENT_FAILED`, and `REFUNDED` -> `REFUNDED`.

### Transactional outbox and Kafka

- The publisher is disabled by default: `KAFKA_OUTBOX_PUBLISHER_ENABLED=false`.
- When enabled, the scheduled publisher claims up to 50 events per batch and 10 batches per run (once per second by default).
- Claiming uses PostgreSQL `FOR UPDATE SKIP LOCKED` with a lease owner and expiration; another instance can reclaim an event only after its lease expires.
- The producer uses `acks=all` and idempotence. An event is marked `PUBLISHED` only after a broker acknowledgement.
- Publish failures use exponential backoff with jitter. An event becomes `FAILED` after `max-attempts` (10 by default).
- Current topic: `payment.requested.v1`; the Kafka key is `paymentId`. Records contain `eventId` and `eventType` headers.
- The payload uses a versioned event envelope:

```json
{
  "eventId": "UUID",
  "eventType": "payment.requested.v1",
  "aggregateId": 123,
  "occurredAt": "2026-11-02T00:00:00Z",
  "traceId": null,
  "payload": {
    "paymentId": 123,
    "orderId": 45,
    "amount": 120000,
    "paymentMethod": "EWALLET"
  }
}
```

Delivery is **at-least-once**: a process may fail after Kafka acknowledges a record but before the database update completes. Future consumers must deduplicate by `eventId`.

## Current API

All request bodies use JSON. Missing resources return `404`; business or unique-constraint conflicts return `409`.

| Area | Endpoint | Function |
| --- | --- | --- |
| Customer | `GET /customers` | List customers |
| Customer | `GET /customers/{id}` | Get a customer |
| Customer | `POST /customers` | Create a customer |
| Customer | `PUT /customers/{id}` | Update a customer |
| Customer | `DELETE /customers/{id}` | Soft-delete a customer |
| Product | `GET /product` | List products |
| Product | `GET /product/{id}` | Get a product |
| Product | `POST /product` | Create a product |
| Product | `PUT /product/{id}` | Update a product |
| Product | `DELETE /product/{id}` | Soft-delete a product |
| Order | `GET /orders` | List orders |
| Order | `GET /orders/{id}` | Get an order |
| Order | `POST /orders` | Create an order |
| Order | `PUT /orders/{id}` | Update an order before payment creation |
| Order | `DELETE /orders/{id}` | Soft-delete an order before payment creation |
| Payment | `GET /payments` | List payments |
| Payment | `GET /payments/{id}` | Get a payment |
| Payment | `POST /payments` | Create a payment and outbox event |
| Payment | `PATCH /payments/{id}/status` | Apply a valid payment status transition |
| Operations | `GET /actuator/health` | Spring Boot Actuator health check |

Example requests:

```bash
curl -X POST http://localhost:8081/customers \
  -H 'Content-Type: application/json' \
  -d '{"firstName":"An","lastName":"Nguyen","email":"an@example.com","phoneNumber":"0900000000"}'

curl -X POST http://localhost:8081/product \
  -H 'Content-Type: application/json' \
  -d '{"productName":"Premium plan","productDescription":"Monthly plan","unitPrice":120000}'

curl -X POST http://localhost:8081/orders \
  -H 'Content-Type: application/json' \
  -d '{"orderCode":"ORD-001","customerId":1,"items":[{"productId":1,"quantity":1}]}'

curl -X POST http://localhost:8081/payments \
  -H 'Content-Type: application/json' \
  -d '{"orderId":1,"method":"EWALLET"}'
```

`PaymentMethod`: `CARD`, `BANK_TRANSFER`, `EWALLET`.

## Run locally with Docker Compose

Docker Desktop is required. Create `.env` only if you need to override the local defaults:

```powershell
Copy-Item .env.example .env
docker compose up --build
```

On macOS/Linux, use `cp .env.example .env` instead of `Copy-Item`.

Compose starts:

- payment service at `http://localhost:8081` (bound to loopback only);
- PostgreSQL at `localhost:5432`;
- Kafka at `localhost:9092`;
- the `payment.requested.v1` topic with three partitions.

Compose sets `spring.jpa.hibernate.ddl-auto=update` for local development. Hibernate
creates missing tables from entity mappings; it is not a production migration strategy.
Introduce versioned migrations before deploying to a shared environment or preserving
data through schema changes.

Supported Compose variables:

| Variable | Default | Purpose |
| --- | --- | --- |
| `POSTGRES_IMAGE` | `postgres:17` | Local PostgreSQL Docker image |
| `POSTGRES_USER` | `payment` | Local database user |
| `POSTGRES_PASSWORD` | `payment` | Local database password |

Do not commit `.env`. `.env.example` is only a local development template; do not use its defaults outside development.

## Run and verify

```bash
./mvnw test
./mvnw spotless:check
```

On Windows PowerShell:

```powershell
.\mvnw.cmd test
.\mvnw.cmd spotless:check
```

Existing unit tests cover payment-event serialization, outbox state/retry behavior, and Kafka publisher records. PostgreSQL/Kafka integration tests—especially atomic rollback, concurrent payment creation, and crash-after-ack behavior—are still required before production.

## Production gaps and risks

- There are no versioned database migrations. Establish a migration baseline for the full schema before deployment to a shared database.
- The API has no authentication/authorization, ownership checks, or idempotency key.
- `existsByOrderId` is an early check; it does not replace the database constraint under concurrent payment-creation requests. `DataIntegrityViolationException` is mapped to `409`.
- There is no Kafka consumer/payment-provider adapter, inbox deduplication, reconciliation, DLT/replay, outbox metrics, or alerting.
- Do not place card data, payment tokens, or PII in Kafka payloads or application logs.

See [docs/kafka-transactional-outbox-roadmap.md](docs/kafka-transactional-outbox-roadmap.md) for the remaining roadmap and acceptance criteria.
