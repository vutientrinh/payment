# Redis guide — OTP codes and rate limiting (backend)

> **When to do this:** after `security-auth-guide.md` is finished (it builds on that code).
> **You get:** the SMS-code step the frontend already expects on register, forgot/reset password,
> a limit on how many codes one phone can request, and a request limit on `/auth/*`.
> **Status of this code:** written against your code base and the security guide, not compiled.
> Each stage ends with a check.

## 0. Redis in five minutes

Redis is a key → value store that lives in memory. Three properties matter here:

| Property | What it gives us |
|---|---|
| **Every key can have a time-to-live (TTL)** | An OTP deletes itself after 5 minutes. No cleanup job |
| **`INCR` is atomic** | Two requests at the same instant get 1 and 2, never 1 and 1. That is a correct counter with no lock |
| **`DEL` tells you if the key existed** | "Use this code once": only the request whose delete returns `true` wins |

And one warning: **Redis is not inside your database transaction.** If the DB rolls back, what you
wrote to Redis stays. So Redis holds only things that are safe to lose or to keep by mistake: codes
and counters. Never money, orders, or the outbox.

### What we store

| Key | Value | TTL | Purpose |
|---|---|---|---|
| `otp:{otpId}` | JSON: user id, purpose, hash of the code, resend time, expiry | 5 min | one pending verification |
| `otp:{otpId}:attempts` | counter | 5 min | wrong guesses on that code |
| `otp:sends:{userId}` | counter | 1 hour | codes sent to one user |
| `rl:auth:{ip}` | counter | 1 min | requests to `/auth/*` from one address |

### The contract (already used by web and mobile, from `frontend/src/mocks/banking.ts`)

| Endpoint | Body | Answer |
|---|---|---|
| `POST /auth/register` | `{phone, password}` | `{otpId, resendAvailableAt, expiresAt}` |
| `POST /auth/otp/verify` | `{otpId, code}` | `{accessToken, refreshToken, user}` |
| `POST /auth/otp/resend` | `{otpId}` | new `{otpId, resendAvailableAt, expiresAt}`; 429 if asked before `resendAvailableAt` |
| `POST /auth/password/forgot` | `{phone}` | `{otpId, resendAvailableAt, expiresAt}` |
| `POST /auth/password/reset` | `{otpId, code, newPassword}` | 204 |

Rules: a code lives 5 minutes, can be resent after 60 seconds, and dies after 5 wrong guesses.
Wrong or expired code → 422. In local development the code comes back in the `X-Mock-OTP`
response header, which the frontend already shows as a toast.

Two deliberate differences from the mock:

- The mock's code is always `123456`. The backend generates a random code.
- The mock answers 404 on `/auth/password/forgot` for an unknown phone. The backend answers the
  same way for known and unknown phones, so nobody can use it to test which numbers have accounts.

### Stages

| Stage | You build | Check |
|---|---|---|
| 1 | Redis in Docker, dependency, settings, test container | app healthy, `redis-cli ping` |
| 2 | Rate limiter (the counter everything else reuses) | unit-style test against real Redis |
| 3 | OTP: store, service, fake SMS sender | unit tests with a fake store |
| 4 | Register → verify → resend | curl |
| 5 | Forgot / reset password | curl |
| 6 | Request limit on `/auth/*` | 21st request in a minute → 429 |

Paths are relative to `src/main/java/com/example/payment/` unless they start with `src/`, `pom` or `docker`.

---

# Stage 1 — Add Redis

### 1.1 `docker-compose.yml`

New service (next to `payment-postgres`):

```yaml
  payment-redis:
    image: redis:7-alpine
    restart: unless-stopped
    # No disk persistence: everything we keep here is short-lived by design.
    command: ["redis-server", "--save", "", "--appendonly", "no"]
    ports:
      - "127.0.0.1:${REDIS_HOST_PORT:-6379}:6379"
    networks:
      - payment-network
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 20
```

In `payment-local`:

```yaml
    environment:
      SPRING_DATA_REDIS_HOST: payment-redis
      OTP_EXPOSE_CODE: "true"          # local only: returns the code in the X-Mock-OTP header
    depends_on:
      payment-redis:
        condition: service_healthy
```

(Add these lines to the existing `environment:` and `depends_on:` blocks; do not create second blocks.)

### 1.2 `pom.xml`

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
```

This gives you a ready `StringRedisTemplate` bean (keys and values are plain strings).

### 1.3 `src/main/resources/application.properties`

```properties
# --- Redis ---
spring.data.redis.host=${SPRING_DATA_REDIS_HOST:localhost}
spring.data.redis.port=${SPRING_DATA_REDIS_PORT:6379}
# Fail fast: a slow Redis must not hang a login request.
spring.data.redis.connect-timeout=2s
spring.data.redis.timeout=2s
# We use Redis as a key-value store only; our repositories are all JPA.
spring.data.redis.repositories.enabled=false

# --- OTP ---
payment.security.otp.ttl=5m
payment.security.otp.resend-delay=60s
payment.security.otp.max-attempts=5
payment.security.otp.max-sends-per-hour=5
# NEVER true in production: it returns the code to the caller.
payment.security.otp.expose-code=${OTP_EXPOSE_CODE:false}

# --- Rate limit ---
payment.security.rate-limit.auth-requests-per-minute=20
```

### 1.4 Settings class

`shared/security/IdentityProperties.java` — add two fields next to the existing ones:

```java
  @Valid private final Otp otp = new Otp();
  @Valid private final RateLimit rateLimit = new RateLimit();
```

and two nested classes:

```java
  @Getter
  @Setter
  public static class Otp {
    @NotNull private Duration ttl = Duration.ofMinutes(5);
    @NotNull private Duration resendDelay = Duration.ofSeconds(60);

    @Min(1)
    private int maxAttempts = 5;

    @Min(1)
    private int maxSendsPerHour = 5;

    private boolean exposeCode = false;
  }

  @Getter
  @Setter
  public static class RateLimit {
    @Min(1)
    private int authRequestsPerMinute = 20;
  }
```

### 1.5 Redis for the tests

`src/test/java/com/example/payment/TestcontainersConfiguration.java` — add a second bean:

```java
  @Bean
  @ServiceConnection(name = "redis")
  GenericContainer<?> redisContainer() {
    return new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
  }
```

Import: `org.testcontainers.containers.GenericContainer`. `@ServiceConnection(name = "redis")`
tells Spring Boot to point `spring.data.redis.*` at this container.

### 1.6 Two more error types

`shared/application/UnprocessableException.java`

```java
package com.example.payment.shared.application;

/** Becomes HTTP 422: the request is well-formed but cannot be done (wrong or expired code). */
public class UnprocessableException extends RuntimeException {
  public UnprocessableException(String message) {
    super(message);
  }
}
```

`shared/application/TooManyRequestsException.java`

```java
package com.example.payment.shared.application;

/** Becomes HTTP 429. */
public class TooManyRequestsException extends RuntimeException {
  public TooManyRequestsException(String message) {
    super(message);
  }
}
```

`shared/api/ApiExceptionHandler.java` — add:

```java
  @ExceptionHandler(UnprocessableException.class)
  public ProblemDetail handleUnprocessable(UnprocessableException exception) {
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage());
  }

  @ExceptionHandler(TooManyRequestsException.class)
  public ProblemDetail handleTooManyRequests(TooManyRequestsException exception) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, exception.getMessage());
  }

  /** Redis is down or too slow. Say "try later"; never show the connection error. */
  @ExceptionHandler({RedisConnectionFailureException.class, QueryTimeoutException.class})
  public ProblemDetail handleRedisUnavailable(Exception exception) {
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.SERVICE_UNAVAILABLE, "Service temporarily unavailable. Try again shortly.");
  }
```

Imports: `org.springframework.data.redis.RedisConnectionFailureException`,
`org.springframework.dao.QueryTimeoutException`.

### ✅ Stage 1 check

```powershell
docker compose up -d --build
docker compose ps                                        # payment-redis healthy, payment-local healthy
docker compose exec payment-redis redis-cli ping         # PONG
curl.exe http://localhost:8081/actuator/health           # {"status":"UP"}
.\mvnw.cmd spotless:apply
.\mvnw.cmd test
```

`/actuator/health` now includes Redis: if Redis is down, health is `DOWN`. That is intended, because
after this guide login-related flows need it.

---

# Stage 2 — The rate limiter

One small component, used three times: OTP sends per user (Stage 3), requests per IP (Stage 6), and
later anything else that needs "at most N per time window".

Port — `shared/ratelimit/RateLimiter.java`

```java
package com.example.payment.shared.ratelimit;

import java.time.Duration;

public interface RateLimiter {
  /**
   * Counts one hit for {@code key}. Returns false when this hit is over {@code limit} within the
   * current {@code window}.
   */
  boolean tryAcquire(String key, int limit, Duration window);
}
```

Implementation — `shared/ratelimit/RedisRateLimiter.java`

```java
package com.example.payment.shared.ratelimit;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Fixed-window counter: INCR the key; the first hit starts the window by setting the TTL. */
@Component
@RequiredArgsConstructor
public class RedisRateLimiter implements RateLimiter {
  private final StringRedisTemplate redis;

  @Override
  public boolean tryAcquire(String key, int limit, Duration window) {
    Long count = redis.opsForValue().increment(key); // atomic, creates the key at 1
    if (count == null) {
      return true;
    }
    if (count == 1L) {
      redis.expire(key, window);
    }
    if (count > limit) {
      // Safety net: if the app died between INCR and EXPIRE, the key has no TTL and would block
      // this caller forever. -1 means "exists, no TTL".
      Long ttlSeconds = redis.getExpire(key);
      if (ttlSeconds != null && ttlSeconds == -1L) {
        redis.expire(key, window);
      }
      return false;
    }
    return true;
  }
}
```

How it works, with limit 3 per minute:

```text
12:00:05  INCR → 1   EXPIRE 60s   allowed
12:00:20  INCR → 2                allowed
12:00:31  INCR → 3                allowed
12:00:40  INCR → 4                BLOCKED
12:01:05  key expired
12:01:10  INCR → 1   EXPIRE 60s   allowed   (new window)
```

- **Why no "read, then write":** `GET` then `SET` lets two requests both read 2 and both write 3.
  `INCR` does read-add-write as one step inside Redis.
- **Known weakness of a fixed window:** 3 hits at 12:00:59 and 3 more at 12:01:01 all pass. That is
  fine for login protection. A sliding window is more exact and more code.

### ✅ Stage 2 check

`src/test/java/com/example/payment/shared/ratelimit/RedisRateLimiterTest.java`

```java
package com.example.payment.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.payment.TestcontainersConfiguration;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RedisRateLimiterTest {
  @Autowired private RateLimiter rateLimiter;
  @Autowired private StringRedisTemplate redis;

  @Test
  void blocksAfterTheLimitAndSetsATtl() {
    String key = "test:rl:" + UUID.randomUUID();

    assertThat(rateLimiter.tryAcquire(key, 2, Duration.ofMinutes(1))).isTrue();
    assertThat(rateLimiter.tryAcquire(key, 2, Duration.ofMinutes(1))).isTrue();
    assertThat(rateLimiter.tryAcquire(key, 2, Duration.ofMinutes(1))).isFalse();

    assertThat(redis.getExpire(key)).isBetween(1L, 60L);
  }

  @Test
  void differentKeysDoNotShareACounter() {
    assertThat(rateLimiter.tryAcquire("test:rl:" + UUID.randomUUID(), 1, Duration.ofMinutes(1)))
        .isTrue();
    assertThat(rateLimiter.tryAcquire("test:rl:" + UUID.randomUUID(), 1, Duration.ofMinutes(1)))
        .isTrue();
  }
}
```

Run `.\mvnw.cmd test`.

---

# Stage 3 — OTP building blocks

### 3.1 Types

`identity/application/otp/OtpPurpose.java`

```java
package com.example.payment.identity.application.otp;

public enum OtpPurpose {
  REGISTER,
  RESET_PASSWORD
}
```

`identity/application/otp/StoredOtp.java` — what sits in Redis:

```java
package com.example.payment.identity.application.otp;

/** Times are epoch milliseconds so the JSON stays trivial. The code itself is never stored. */
public record StoredOtp(
    Long userId, OtpPurpose purpose, String codeHash, long resendAvailableAtMs, long expiresAtMs) {}
```

`identity/application/otp/OtpChallenge.java` — what the service hands back:

```java
package com.example.payment.identity.application.otp;

import java.time.Instant;

/**
 * {@code code} is the plain code. It exists only so local development can show it; it is null for a
 * decoy challenge. Never log it and never put it in a response body.
 */
public record OtpChallenge(String otpId, Instant resendAvailableAt, Instant expiresAt, String code) {}
```

### 3.2 Store

Port — `identity/application/otp/OtpChallengeStore.java`

```java
package com.example.payment.identity.application.otp;

import java.time.Duration;
import java.util.Optional;

public interface OtpChallengeStore {
  void save(String otpId, StoredOtp otp, Duration ttl);

  Optional<StoredOtp> find(String otpId);

  /** Adds one wrong-or-right guess and returns the new total. */
  long incrementAttempts(String otpId, Duration ttl);

  /** Removes the challenge. Returns true only for the caller that actually removed it. */
  boolean delete(String otpId);
}
```

Implementation — `identity/infrastructure/redis/RedisOtpChallengeStore.java`

```java
package com.example.payment.identity.infrastructure.redis;

import com.example.payment.identity.application.otp.OtpChallengeStore;
import com.example.payment.identity.application.otp.StoredOtp;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
public class RedisOtpChallengeStore implements OtpChallengeStore {
  private final StringRedisTemplate redis;
  private final JsonMapper jsonMapper;

  @Override
  public void save(String otpId, StoredOtp otp, Duration ttl) {
    // SET with a TTL is ONE command: the key can never exist without an expiry.
    redis.opsForValue().set(key(otpId), jsonMapper.writeValueAsString(otp), ttl);
  }

  @Override
  public Optional<StoredOtp> find(String otpId) {
    String json = redis.opsForValue().get(key(otpId));
    return json == null
        ? Optional.empty()
        : Optional.of(jsonMapper.readValue(json, StoredOtp.class));
  }

  @Override
  public long incrementAttempts(String otpId, Duration ttl) {
    String attemptsKey = key(otpId) + ":attempts";
    Long attempts = redis.opsForValue().increment(attemptsKey);
    redis.expire(attemptsKey, ttl); // cannot outlive the challenge by more than one TTL
    return attempts == null ? 1L : attempts;
  }

  @Override
  public boolean delete(String otpId) {
    redis.delete(key(otpId) + ":attempts");
    return Boolean.TRUE.equals(redis.delete(key(otpId)));
  }

  private static String key(String otpId) {
    return "otp:" + otpId;
  }
}
```

Why the attempts counter is a separate key: `INCR` works on a plain number. Updating a number
inside the JSON would need read-change-write, which is not atomic.

### 3.3 Sender (the "SMS")

Port — `identity/application/otp/OtpSender.java`

```java
package com.example.payment.identity.application.otp;

public interface OtpSender {
  void send(String phone, String code, OtpPurpose purpose);
}
```

Simulation — `identity/infrastructure/otp/LoggingOtpSender.java`

```java
package com.example.payment.identity.infrastructure.otp;

import com.example.payment.identity.application.otp.OtpPurpose;
import com.example.payment.identity.application.otp.OtpSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Stands in for an SMS provider. A real one implements the same interface. */
@Slf4j
@Component
public class LoggingOtpSender implements OtpSender {
  @Override
  public void send(String phone, String code, OtpPurpose purpose) {
    String masked = phone == null || phone.length() < 3 ? "***" : "***" + phone.substring(phone.length() - 3);
    log.info("OTP ({}) sent to {}", purpose, masked); // the code is NOT logged
  }
}
```

### 3.4 Service

`identity/application/otp/OtpService.java`

```java
package com.example.payment.identity.application.otp;

import com.example.payment.customer.domain.UserEntity;
import com.example.payment.shared.application.TooManyRequestsException;
import com.example.payment.shared.application.UnprocessableException;
import com.example.payment.shared.ratelimit.RateLimiter;
import com.example.payment.shared.security.IdentityProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OtpService {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String EXPIRED = "Code expired. Request a new code.";

  private final OtpChallengeStore store;
  private final RateLimiter rateLimiter;
  private final OtpSender sender;
  private final IdentityProperties properties;

  /** Creates a code, stores its hash, "sends" it. */
  public OtpChallenge create(UserEntity user, OtpPurpose purpose) {
    IdentityProperties.Otp config = properties.getOtp();
    boolean allowed =
        rateLimiter.tryAcquire(
            "otp:sends:" + user.getId(), config.getMaxSendsPerHour(), Duration.ofHours(1));
    if (!allowed) {
      throw new TooManyRequestsException("Too many codes requested. Try again later.");
    }

    String otpId = UUID.randomUUID().toString();
    String code = String.format("%06d", RANDOM.nextInt(1_000_000));
    Instant now = Instant.now();
    Instant resendAvailableAt = now.plus(config.getResendDelay());
    Instant expiresAt = now.plus(config.getTtl());

    store.save(
        otpId,
        new StoredOtp(
            user.getId(),
            purpose,
            hash(otpId, code),
            resendAvailableAt.toEpochMilli(),
            expiresAt.toEpochMilli()),
        config.getTtl());
    sender.send(user.getPhoneNumber(), code, purpose);
    return new OtpChallenge(otpId, resendAvailableAt, expiresAt, code);
  }

  /** Looks like a real challenge but nothing is stored or sent. Used for unknown phones. */
  public OtpChallenge decoy() {
    Instant now = Instant.now();
    return new OtpChallenge(
        UUID.randomUUID().toString(),
        now.plus(properties.getOtp().getResendDelay()),
        now.plus(properties.getOtp().getTtl()),
        null);
  }

  /** Returns the user id when the code is right. A code works once. */
  public Long verify(String otpId, String code, OtpPurpose expectedPurpose) {
    StoredOtp otp =
        store
            .find(otpId)
            .filter(found -> found.purpose() == expectedPurpose)
            .orElseThrow(() -> new UnprocessableException(EXPIRED));

    // Count FIRST, compare second: parallel guesses cannot slip past the limit.
    long attempts = store.incrementAttempts(otpId, properties.getOtp().getTtl());
    if (attempts > properties.getOtp().getMaxAttempts()) {
      store.delete(otpId);
      throw new UnprocessableException("Too many attempts. Request a new code.");
    }

    boolean matches =
        MessageDigest.isEqual( // constant-time compare
            hash(otpId, code).getBytes(StandardCharsets.UTF_8),
            otp.codeHash().getBytes(StandardCharsets.UTF_8));
    if (!matches) {
      throw new UnprocessableException("Incorrect verification code");
    }

    // Single use: if two requests send the right code together, only one delete returns true.
    if (!store.delete(otpId)) {
      throw new UnprocessableException(EXPIRED);
    }
    return otp.userId();
  }

  /** Checks that a resend is allowed and removes the old challenge. Returns what it was for. */
  public StoredOtp takeForResend(String otpId) {
    StoredOtp otp =
        store
            .find(otpId)
            .orElseThrow(() -> new UnprocessableException("Verification request expired."));
    if (Instant.now().toEpochMilli() < otp.resendAvailableAtMs()) {
      throw new TooManyRequestsException("Please wait before requesting another code.");
    }
    store.delete(otpId);
    return otp;
  }

  private static String hash(String otpId, String code) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest((otpId + ":" + code).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is always available", e);
    }
  }
}
```

Why each protection exists — a 6-digit code has only 1,000,000 possibilities:

| Protection | Stops |
|---|---|
| 5 guesses per code | guessing one code |
| 5 codes per user per hour | "ask for a new code, get 5 more guesses" in a loop. Together: at most 25 guesses per hour, about a 0.0025% chance |
| 5-minute TTL | a code found later in an old SMS |
| `SecureRandom` | predicting the next code (`Math.random` is predictable) |
| single use (`delete` returns true once) | replaying a code that was just used |
| hash in Redis | someone with Redis access reading live codes |

### ✅ Stage 3 check — unit tests with a fake store (no Redis needed)

`src/test/java/com/example/payment/identity/application/otp/OtpServiceTest.java`

```java
package com.example.payment.identity.application.otp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.payment.customer.domain.UserEntity;
import com.example.payment.shared.application.TooManyRequestsException;
import com.example.payment.shared.application.UnprocessableException;
import com.example.payment.shared.security.IdentityProperties;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class OtpServiceTest {
  private final FakeStore store = new FakeStore();
  private final IdentityProperties properties = new IdentityProperties();
  private boolean sendAllowed = true;
  private OtpService service;
  private UserEntity user;

  @BeforeEach
  void setUp() {
    service =
        new OtpService(store, (key, limit, window) -> sendAllowed, (phone, code, purpose) -> {}, properties);
    user = new UserEntity();
    ReflectionTestUtils.setField(user, "id", 7L);
    user.setPhoneNumber("0900000001");
  }

  @Test
  void rightCodeReturnsTheUserAndWorksOnlyOnce() {
    OtpChallenge challenge = service.create(user, OtpPurpose.REGISTER);

    assertThat(service.verify(challenge.otpId(), challenge.code(), OtpPurpose.REGISTER)).isEqualTo(7L);
    assertThatThrownBy(
            () -> service.verify(challenge.otpId(), challenge.code(), OtpPurpose.REGISTER))
        .isInstanceOf(UnprocessableException.class);
  }

  @Test
  void theCodeItselfIsNotStored() {
    OtpChallenge challenge = service.create(user, OtpPurpose.REGISTER);

    assertThat(store.values.get(challenge.otpId()).codeHash()).doesNotContain(challenge.code());
  }

  @Test
  void aCodeForOnePurposeCannotBeUsedForAnother() {
    OtpChallenge challenge = service.create(user, OtpPurpose.REGISTER);

    assertThatThrownBy(
            () -> service.verify(challenge.otpId(), challenge.code(), OtpPurpose.RESET_PASSWORD))
        .isInstanceOf(UnprocessableException.class);
  }

  @Test
  void sixthGuessKillsTheChallengeEvenIfCorrect() {
    OtpChallenge challenge = service.create(user, OtpPurpose.REGISTER);
    String wrong = challenge.code().equals("000000") ? "111111" : "000000";

    for (int attempt = 1; attempt <= 5; attempt++) {
      assertThatThrownBy(() -> service.verify(challenge.otpId(), wrong, OtpPurpose.REGISTER))
          .hasMessage("Incorrect verification code");
    }
    assertThatThrownBy(
            () -> service.verify(challenge.otpId(), challenge.code(), OtpPurpose.REGISTER))
        .hasMessage("Too many attempts. Request a new code.");
    assertThat(store.values).doesNotContainKey(challenge.otpId());
  }

  @Test
  void resendIsRefusedBeforeTheDelay() {
    OtpChallenge challenge = service.create(user, OtpPurpose.REGISTER);

    assertThatThrownBy(() -> service.takeForResend(challenge.otpId()))
        .isInstanceOf(TooManyRequestsException.class);
  }

  @Test
  void sendLimitStopsNewCodes() {
    sendAllowed = false;

    assertThatThrownBy(() -> service.create(user, OtpPurpose.REGISTER))
        .isInstanceOf(TooManyRequestsException.class);
    assertThat(store.values).isEmpty();
  }

  /** In-memory stand-in for Redis. This is why the store is an interface. */
  private static class FakeStore implements OtpChallengeStore {
    final Map<String, StoredOtp> values = new HashMap<>();
    final Map<String, Long> attempts = new HashMap<>();

    @Override
    public void save(String otpId, StoredOtp otp, Duration ttl) {
      values.put(otpId, otp);
    }

    @Override
    public Optional<StoredOtp> find(String otpId) {
      return Optional.ofNullable(values.get(otpId));
    }

    @Override
    public long incrementAttempts(String otpId, Duration ttl) {
      return attempts.merge(otpId, 1L, Long::sum);
    }

    @Override
    public boolean delete(String otpId) {
      attempts.remove(otpId);
      return values.remove(otpId) != null;
    }
  }
}
```

Run `.\mvnw.cmd spotless:apply` then `.\mvnw.cmd test`.

---

# Stage 4 — Register with a code

This **replaces** `register` from the security guide (it returned tokens at once).

### 4.1 New status

`customer/domain/UserStatus.java` — add `UNVERIFIED`:

```java
public enum UserStatus {
  UNVERIFIED,
  ACTIVE,
  SUSPENDED
}
```

No migration: the column is text. `login` already refuses every status except `ACTIVE`, so an
unverified user cannot sign in.

### 4.2 Service

`identity/application/AuthService.java` — change `register`, add two methods:

```java
  OtpChallenge register(String phone, String password);   // was: AuthResult

  AuthResult verifyRegistration(String otpId, String code);

  OtpChallenge resendOtp(String otpId);
```

`identity/application/AuthServiceImpl.java` — add the field:

```java
  private final OtpService otpService;
```

Replace `register` and add the other two (imports: `OtpChallenge`, `OtpPurpose`, `OtpService`,
`StoredOtp`, `UnprocessableException`):

```java
  @Override
  public OtpChallenge register(String phone, String password) {
    // A phone that started registering but never entered the code may start again.
    UserEntity user = userRepository.findByPhoneNumber(phone).orElseGet(UserEntity::new);
    if (user.getId() != null && user.getStatus() != UserStatus.UNVERIFIED) {
      throw new ConflictException("Phone already registered.");
    }
    user.setPhoneNumber(phone);
    user.setPasswordHash(passwordEncoder.encode(password));
    user.setStatus(UserStatus.UNVERIFIED);
    user.getRoles().add(Role.SHOPPER);
    user = userRepository.save(user);
    return otpService.create(user, OtpPurpose.REGISTER);
  }

  @Override
  public AuthResult verifyRegistration(String otpId, String code) {
    Long userId = otpService.verify(otpId, code, OtpPurpose.REGISTER);
    UserEntity user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new UnprocessableException("Invalid verification request"));
    user.setStatus(UserStatus.ACTIVE); // the phone is now proven to belong to this person
    return issueTokens(user);
  }

  @Override
  public OtpChallenge resendOtp(String otpId) {
    StoredOtp old = otpService.takeForResend(otpId);
    UserEntity user =
        userRepository
            .findById(old.userId())
            .orElseThrow(() -> new UnprocessableException("Verification request expired."));
    return otpService.create(user, old.purpose());
  }
```

Without the "start again" rule in `register`, a typo'd or abandoned registration would block that
phone number forever. The password is replaced each time, so whoever finally proves the phone owns
the account.

### 4.3 API

`identity/api/request/VerifyOtpRequest.java`

```java
package com.example.payment.identity.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerifyOtpRequest(
    @NotBlank String otpId,
    @NotBlank @Pattern(regexp = "^\\d{6}$", message = "must be 6 digits") String code) {}
```

`identity/api/request/ResendOtpRequest.java`

```java
package com.example.payment.identity.api.request;

import jakarta.validation.constraints.NotBlank;

public record ResendOtpRequest(@NotBlank String otpId) {}
```

`identity/api/response/OtpChallengeResponse.java`

```java
package com.example.payment.identity.api.response;

import com.example.payment.identity.application.otp.OtpChallenge;

/** ISO-8601 UTC strings, as the frontend expects. The code is deliberately not here. */
public record OtpChallengeResponse(String otpId, String resendAvailableAt, String expiresAt) {
  public static OtpChallengeResponse from(OtpChallenge challenge) {
    return new OtpChallengeResponse(
        challenge.otpId(),
        challenge.resendAvailableAt().toString(),
        challenge.expiresAt().toString());
  }
}
```

`identity/api/AuthController.java` — add the field `private final IdentityProperties properties;`,
**replace** the `register` method, and add the rest:

```java
  @PostMapping("/register")
  public ResponseEntity<OtpChallengeResponse> register(@Valid @RequestBody RegisterRequest request) {
    return otpResponse(authService.register(request.phone(), request.password()));
  }

  @PostMapping("/otp/verify")
  public ResponseEntity<TokensResponse> verifyOtp(@Valid @RequestBody VerifyOtpRequest request) {
    return ResponseEntity.ok(
        TokensResponse.from(authService.verifyRegistration(request.otpId(), request.code())));
  }

  @PostMapping("/otp/resend")
  public ResponseEntity<OtpChallengeResponse> resendOtp(
      @Valid @RequestBody ResendOtpRequest request) {
    return otpResponse(authService.resendOtp(request.otpId()));
  }

  /** In local development only, the code travels in a header so you can test without SMS. */
  private ResponseEntity<OtpChallengeResponse> otpResponse(OtpChallenge challenge) {
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (properties.getOtp().isExposeCode() && challenge.code() != null) {
      response.header("X-Mock-OTP", challenge.code());
    }
    return response.body(OtpChallengeResponse.from(challenge));
  }
```

### ✅ Stage 4 check (Git Bash)

```bash
API=http://localhost:8081
curl -s -i -X POST $API/auth/register -H 'Content-Type: application/json' \
  -d '{"phone":"0900000002","password":"secret-123"}'
# 200, header  X-Mock-OTP: 482913 , body {"otpId":"...","resendAvailableAt":"...","expiresAt":"..."}

OTP_ID=<paste otpId>;  CODE=<paste header value>

curl -s -i -X POST $API/auth/login -H 'Content-Type: application/json' \
  -d '{"login":"0900000002","password":"secret-123"}'            # 401: not verified yet

curl -s -i -X POST $API/auth/otp/resend -H 'Content-Type: application/json' \
  -d "{\"otpId\":\"$OTP_ID\"}"                                    # 429: under 60 s

curl -s -i -X POST $API/auth/otp/verify -H 'Content-Type: application/json' \
  -d "{\"otpId\":\"$OTP_ID\",\"code\":\"000000\"}"                # 422 Incorrect verification code

curl -s -X POST $API/auth/otp/verify -H 'Content-Type: application/json' \
  -d "{\"otpId\":\"$OTP_ID\",\"code\":\"$CODE\"}"                 # 200 + tokens
```

Look inside Redis while a code is pending:

```bash
docker compose exec payment-redis redis-cli KEYS 'otp:*'
docker compose exec payment-redis redis-cli TTL  otp:<otpId>       # seconds left, counts down
docker compose exec payment-redis redis-cli GET  otp:<otpId>       # codeHash, not the code
```

(`KEYS` scans everything. Fine on your laptop; never run it on a production Redis.)

Now the real frontend's register screen works against the backend.

---

# Stage 5 — Forgot and reset password

### 5.1 Sign every device out

After a password reset, old sessions must die: the reason for a reset is often that someone else
knows the old password.

`identity/application/RefreshTokenRepository.java` — add:

```java
  void revokeAllForUser(Long userId, LocalDateTime now);
```

`identity/infrastructure/repository/JpaDataRefreshTokenRepository.java` — add:

```java
  @Modifying
  @Query(
      "update RefreshTokenEntity t set t.revokedAt = :now "
          + "where t.userId = :userId and t.revokedAt is null")
  int revokeAllForUser(@Param("userId") Long userId, @Param("now") LocalDateTime now);
```

`identity/infrastructure/repository/JpaRefreshTokenRepository.java` — add:

```java
  @Override
  public void revokeAllForUser(Long userId, LocalDateTime now) {
    jpaDataRefreshTokenRepository.revokeAllForUser(userId, now);
  }
```

`identity/application/RefreshTokenService.java` — add:

```java
  /** Password reset or account takeover: end every login of this user. */
  public void revokeAllForUser(Long userId) {
    refreshTokenRepository.revokeAllForUser(userId, LocalDateTime.now());
  }
```

### 5.2 Service

`AuthService` — add:

```java
  OtpChallenge forgotPassword(String phone);

  void resetPassword(String otpId, String code, String newPassword);
```

`AuthServiceImpl` — add:

```java
  @Override
  public OtpChallenge forgotPassword(String phone) {
    // Same answer whether the phone exists or not.
    return userRepository
        .findByPhoneNumber(phone)
        .filter(user -> user.getStatus() == UserStatus.ACTIVE)
        .map(user -> otpService.create(user, OtpPurpose.RESET_PASSWORD))
        .orElseGet(otpService::decoy);
  }

  @Override
  public void resetPassword(String otpId, String code, String newPassword) {
    Long userId = otpService.verify(otpId, code, OtpPurpose.RESET_PASSWORD);
    UserEntity user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new UnprocessableException("Invalid verification request"));
    user.setPasswordHash(passwordEncoder.encode(newPassword));
    user.recordSuccessfulLogin(); // clears the wrong-password counter and any lock
    refreshTokenService.revokeAllForUser(userId);
  }
```

The purpose check in `verify` is what stops a *register* code from being used to *reset* someone's
password. The Stage 3 test `aCodeForOnePurposeCannotBeUsedForAnother` covers it.

### 5.3 API

`identity/api/request/ForgotPasswordRequest.java`

```java
package com.example.payment.identity.api.request;

import jakarta.validation.constraints.NotBlank;

public record ForgotPasswordRequest(@NotBlank String phone) {}
```

`identity/api/request/ResetPasswordRequest.java`

```java
package com.example.payment.identity.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
    @NotBlank String otpId,
    @NotBlank @Pattern(regexp = "^\\d{6}$", message = "must be 6 digits") String code,
    @NotBlank @Size(min = 8, max = 72, message = "must be 8 to 72 characters") String newPassword) {}
```

`AuthController` — add:

```java
  @PostMapping("/password/forgot")
  public ResponseEntity<OtpChallengeResponse> forgotPassword(
      @Valid @RequestBody ForgotPasswordRequest request) {
    return otpResponse(authService.forgotPassword(request.phone()));
  }

  @PostMapping("/password/reset")
  public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
    authService.resetPassword(request.otpId(), request.code(), request.newPassword());
    return ResponseEntity.noContent().build();
  }
```

### ✅ Stage 5 check

```bash
curl -s -i -X POST $API/auth/password/forgot -H 'Content-Type: application/json' \
  -d '{"phone":"0900000002"}'                                    # 200 + X-Mock-OTP
curl -s -i -X POST $API/auth/password/forgot -H 'Content-Type: application/json' \
  -d '{"phone":"0911111111"}'                                    # 200, same body shape, no account

curl -s -i -X POST $API/auth/password/reset -H 'Content-Type: application/json' \
  -d "{\"otpId\":\"$OTP_ID\",\"code\":\"$CODE\",\"newPassword\":\"new-secret-456\"}"   # 204
```

Then: login with the old password → 401; with the new one → 200; an old refresh token on
`/auth/refresh` → 401.

In local mode the unknown phone has no `X-Mock-OTP` header, which does reveal the difference. That
only happens with `OTP_EXPOSE_CODE=true`; in production both answers are identical.

---

# Stage 6 — Limit requests to `/auth/*`

Lockout (security guide) protects **one account** from many guesses. This protects **all accounts**
from one attacker trying a common password on thousands of phone numbers.

`shared/security/AuthRateLimitFilter.java`

```java
package com.example.payment.shared.security;

import com.example.payment.shared.ratelimit.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthRateLimitFilter extends OncePerRequestFilter {
  private static final Duration WINDOW = Duration.ofMinutes(1);

  private final RateLimiter rateLimiter;
  private final IdentityProperties properties;
  private final JsonMapper jsonMapper;

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/auth/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (isAllowed(request.getRemoteAddr())) {
      chain.doFilter(request, response);
      return;
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "about:blank");
    body.put("title", HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase());
    body.put("status", HttpStatus.TOO_MANY_REQUESTS.value());
    body.put("detail", "Too many requests. Try again in a minute.");

    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setHeader("Retry-After", String.valueOf(WINDOW.toSeconds()));
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    jsonMapper.writeValue(response.getOutputStream(), body);
  }

  private boolean isAllowed(String clientAddress) {
    try {
      return rateLimiter.tryAcquire(
          "rl:auth:" + clientAddress,
          properties.getRateLimit().getAuthRequestsPerMinute(),
          WINDOW);
    } catch (DataAccessException exception) {
      // Redis is down. Let the request through (see the table below) but make noise.
      log.warn("Rate limiter unavailable — allowing request: {}", exception.getMessage());
      return true;
    }
  }
}
```

A `@Component` that is a servlet `Filter` is registered by Spring Boot automatically; there is
nothing to add to `SecurityConfiguration`.

### What happens when Redis is down? Decide per feature

| Feature | Redis down → | Why |
|---|---|---|
| Rate limit | **allow** (fail open) | Blocking all logins because a counter is unavailable is a bigger outage than the risk. Account lockout in the DB still works |
| OTP create / verify | **refuse** with 503 (fail closed) | Without the store we cannot check a code. Letting people in unverified is not an option |

This is the question to ask for every future Redis feature, before writing the code.

### About the client address

`getRemoteAddr()` is the address of whoever connected to the app. Locally that is you. Behind a
load balancer or reverse proxy it is the **proxy**, so every user would share one counter. When you
deploy behind one, set `server.forward-headers-strategy=native` so Spring reads the real address
from `X-Forwarded-For`, and make sure only your proxy can set that header.

### ✅ Stage 6 check

```bash
for i in $(seq 1 22); do
  curl -s -o /dev/null -w "%{http_code} " -X POST $API/auth/login \
    -H 'Content-Type: application/json' -d '{"login":"0900000009","password":"x"}'
done; echo
# 401 401 ... (20 times) ... 429 429
docker compose exec payment-redis redis-cli --scan --pattern 'rl:auth:*'
```

Then stop Redis and try again:

```bash
docker compose stop payment-redis
curl -s -i -X POST $API/auth/login -H 'Content-Type: application/json' \
  -d '{"login":"0900000002","password":"new-secret-456"}'      # 200: login still works (fail open)
curl -s -i -X POST $API/auth/register -H 'Content-Type: application/json' \
  -d '{"phone":"0900000003","password":"secret-123"}'          # 503: cannot create a code (fail closed)
docker compose start payment-redis
```

(Each call may take about 2 seconds while Redis is down. That is the timeout from Stage 1.3.)

---

## Done checklist

- [ ] `payment-redis` healthy; app health `UP`; `mvnw test` green with the Redis test container
- [ ] Register returns `{otpId, resendAvailableAt, expiresAt}`; the user cannot log in until verified
- [ ] Right code → tokens; the same code a second time → 422
- [ ] 6th guess → 422 "Too many attempts", and the key is gone from Redis
- [ ] Resend before 60 s → 429; 6th code in an hour → 429
- [ ] Forgot password answers the same for known and unknown phones
- [ ] Reset password signs out every device (old refresh tokens → 401)
- [ ] 21st `/auth/*` request in a minute → 429 with `Retry-After`
- [ ] Redis stopped: login works, register returns 503
- [ ] `OTP_EXPOSE_CODE` is `true` only in the local compose file; no code appears in any log line
- [ ] Register and forgot-password work from the real web frontend

## Not in this guide (next Redis uses)

| Item | Where it fits |
|---|---|
| OTP on login from a new device (`otpRequired: true`) | Needs a device list first (IAM-06). `OtpPurpose.LOGIN` + the same `OtpService` |
| Step-up OTP for transfers above 2,000,000 ₫ | With the wallet backend (IAM-07). Same `OtpService`, purpose `TRANSFER` |
| Idempotency keys on `POST /payments`, top-up, transfer | With the payment/wallet APIs. Key `idem:{userId}:{key}` → stored response, 24 h TTL |
| Access-token denylist / "user suspended" flag | Makes logout and bans instant instead of "within 15 minutes" |
| Cache for products and categories, cart | With the marketplace backend (P2) |
| Move login lockout counters from `users` to Redis | Optional. Saves a DB write per wrong password |
