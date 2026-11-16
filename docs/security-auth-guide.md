# Security guide — JWT login, Google sign-in, admin/user permissions (backend)

> **When to do this:** after Task 4 (payment executor) and the basic payment APIs are finished.
> **Stack:** Spring Boot 4.1, Spring Security 7, Jackson 3 (`tools.jackson`), Flyway, PostgreSQL.
> **Status of this code:** written against your code base but not compiled. Each stage ends with a
> check, so a wrong import shows up early.

## 0. The big picture

```text
                  ┌──────────────────────── public ────────────────────────┐
 POST /auth/register {phone,password}      ─┐
 POST /auth/login    {login,password}       ├─►  { accessToken, refreshToken, user }
 POST /auth/social/google {idToken}        ─┘
 POST /auth/refresh  {refreshToken}        ───►  new pair (old refresh token is dead)
 POST /auth/logout   {refreshToken}        ───►  204
                  └─────────────────────────────────────────────────────────┘

 every other request:   Authorization: Bearer <accessToken>
        │
        ▼
 Spring Security filter ─ verifies signature + expiry ─ reads "roles" claim
        │                                   │
        │ no/invalid token → 401            │ wrong role → 403
        ▼
 Controller  (@PreAuthorize / URL rules decide ADMIN vs SHOPPER)
```

### Two tokens, two jobs

| | Access token | Refresh token |
|---|---|---|
| What | JWT signed by us (HS256) | 32 random bytes, no meaning inside |
| Lives | 15 minutes | 30 days |
| Stored in DB | No. The signature is the proof | Yes, but only its SHA-256 hash |
| Sent | On every request | Only to `/auth/refresh` and `/auth/logout` |
| Can be cancelled | No. It just expires | Yes (logout, theft detection) |

A short access token limits the damage if one leaks. The refresh token is what lets the user stay
signed in, and because it is in the database we can cancel it.

### Decisions made in this guide (tell the Lead if you want another choice)

| # | Decision | Why |
|---|---|---|
| 1 | Paths and JSON match what the web/mobile apps already call (`frontend/src/mocks/banking.ts`) | The apps then work against the real backend with no rewrite. The Vite proxy strips `/api`, so the backend path is `/auth/login`, not `/api/auth/login` |
| 2 | Reuse the `users` table; add password, status, roles | One user concept. Orders already point at `users.id` |
| 3 | Roles: `SHOPPER`, `SELLER_OWNER`, `SELLER_STAFF`, `ADMIN`, `FINANCE`, `SUPPORT` | Same list as the frontend. "User" = `SHOPPER`. This guide uses `SHOPPER` and `ADMIN` |
| 4 | Use Spring Security's built-in JWT support (resource server + Nimbus), no hand-written filter | Less code to get wrong |
| 5 | Social login = **Google ID token**: the app gets a token from Google, sends it to us, we verify it and issue **our own** tokens | Same flow for web and mobile; the backend needs no redirect pages or sessions |
| 6 | **Not in this guide:** SMS OTP (sim), forgot password, new-device OTP | Next guide. Until then `register` returns tokens directly, while the frontend expects an OTP step first. Register from the real frontend will not work until the OTP guide is done; login works |

Social login is not in `docs/product/features.md` yet (IAM-01…09 list phone + password only), and
the web/mobile apps have no Google button. That is a scope addition for the Lead to record.

### Stages

| Stage | You build | Check |
|---|---|---|
| 1 | Dependencies, migration, user fields | existing tests green |
| 2 | Lock everything: security config, 401/403 as JSON | `curl /orders` → 401 |
| 3 | Token code: JWT issuer, refresh-token store | unit tests |
| 4 | `/auth/*` endpoints and `/me` | login with curl |
| 5 | Permissions: admin vs shopper, own data only | 403 / 404 with curl |
| 6 | Google sign-in | Google token → our tokens |
| 7 | Tests | `mvnw test` |

Paths are relative to `src/main/java/com/example/payment/` unless they start with `src/` or `pom`.

---

# Stage 1 — Dependencies, database, user fields

### 1.1 `pom.xml`

Add inside `<dependencies>`:

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security-oauth2-resource-server</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security-test</artifactId>
            <scope>test</scope>
        </dependency>
```

- The first one brings Spring Security **and** the JWT library (Nimbus). "Resource server" is
  Spring's name for "an API that accepts bearer tokens".
- Spring Boot 4 renamed this starter. Guides for Boot 3 say `spring-boot-starter-oauth2-resource-server`.

⚠️ The moment this dependency is on the classpath, **every endpoint needs a login**. That is the
correct default. Stage 2 opens the public ones.

### 1.2 Migration

`src/main/resources/db/migration/V5__identity.sql` (use the next free number if V5 is taken):

```sql
-- Users can now register with only a phone, or only a Google email.
ALTER TABLE users
    ALTER COLUMN first_name   DROP NOT NULL,
    ALTER COLUMN last_name    DROP NOT NULL,
    ALTER COLUMN email        DROP NOT NULL,
    ALTER COLUMN phone_number DROP NOT NULL,
    ADD COLUMN password_hash      VARCHAR(255),
    ADD COLUMN status             VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN failed_login_count INT         NOT NULL DEFAULT 0,
    ADD COLUMN locked_until       TIMESTAMP,
    ADD CONSTRAINT uk_users_phone_number UNIQUE (phone_number);

CREATE TABLE user_roles (
    user_id BIGINT      NOT NULL,
    role    VARCHAR(32) NOT NULL,
    CONSTRAINT pk_user_roles      PRIMARY KEY (user_id, role),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users (id)
);
-- Existing customers become shoppers.
INSERT INTO user_roles (user_id, role) SELECT id, 'SHOPPER' FROM users;

CREATE TABLE refresh_tokens (
    id          BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_id     BIGINT      NOT NULL,
    token_hash  VARCHAR(64) NOT NULL,          -- SHA-256 hex of the token, never the token itself
    family_id   UUID        NOT NULL,          -- all tokens that came from one login
    expires_at  TIMESTAMP   NOT NULL,
    revoked_at  TIMESTAMP,
    created_at  TIMESTAMP   NOT NULL,
    CONSTRAINT uk_refresh_tokens_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id)
);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);

CREATE TABLE social_identities (
    id          BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    provider    VARCHAR(32)  NOT NULL,         -- 'GOOGLE'
    subject     VARCHAR(255) NOT NULL,         -- Google's permanent user id ("sub")
    created_at  TIMESTAMP    NOT NULL,
    CONSTRAINT uk_social_identities_provider_subject UNIQUE (provider, subject),
    CONSTRAINT fk_social_identities_user FOREIGN KEY (user_id) REFERENCES users (id)
);
```

- If your local DB has two customers with the same phone, the unique constraint fails. Reset with
  `docker compose down -v`.
- PostgreSQL allows many rows with `NULL` in a unique column, so phone-less Google users are fine.
- Why hash the refresh token: if someone reads the table, they still cannot use any token.

### 1.3 Role and status enums

`customer/domain/Role.java`

```java
package com.example.payment.customer.domain;

public enum Role {
  SHOPPER,
  SELLER_OWNER,
  SELLER_STAFF,
  ADMIN,
  FINANCE,
  SUPPORT
}
```

`customer/domain/UserStatus.java`

```java
package com.example.payment.customer.domain;

public enum UserStatus {
  ACTIVE,
  SUSPENDED
}
```

### 1.4 `customer/domain/UserEntity.java` (replace)

```java
package com.example.payment.customer.domain;

import com.example.payment.shared.domain.BaseEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "users")
public class UserEntity extends BaseEntity {
  private String firstName;
  private String lastName;

  @Column(unique = true)
  private String email;

  @Column(unique = true)
  private String phoneNumber;

  /** Null for accounts that only sign in with Google. */
  private String passwordHash;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private UserStatus status = UserStatus.ACTIVE;

  @Column(nullable = false)
  private int failedLoginCount;

  private LocalDateTime lockedUntil;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
  @Column(name = "role", nullable = false, length = 32)
  @Enumerated(EnumType.STRING)
  private Set<Role> roles = new HashSet<>();

  public boolean isLocked(LocalDateTime now) {
    return lockedUntil != null && lockedUntil.isAfter(now);
  }

  /** Business rule: {@code maxAttempts} wrong passwords lock the login for {@code lockDuration}. */
  public void recordFailedLogin(LocalDateTime now, int maxAttempts, Duration lockDuration) {
    failedLoginCount++;
    if (failedLoginCount >= maxAttempts) {
      failedLoginCount = 0;
      lockedUntil = now.plus(lockDuration);
    }
  }

  public void recordSuccessfulLogin() {
    failedLoginCount = 0;
    lockedUntil = null;
  }

  public String fullName() {
    return Stream.of(firstName, lastName)
        .filter(part -> part != null && !part.isBlank())
        .collect(Collectors.joining(" "));
  }
}
```

`passwordHash` must never appear in a response. Your API already maps entities to explicit
records (`CustomerResponse`), so nothing leaks. Keep it that way; never return an entity.

### 1.5 User lookups

`customer/application/CustomerRepository.java` — add:

```java
  Optional<UserEntity> findByPhoneNumber(String phoneNumber);

  Optional<UserEntity> findByEmail(String email);

  boolean existsByPhoneNumber(String phoneNumber);
```

`customer/infrastructure/repository/JpaDataUserRepository.java` — add:

```java
  @Query("select u from UserEntity u where u.phoneNumber = :phone and u.isActive = true")
  Optional<UserEntity> findActiveByPhoneNumber(@Param("phone") String phone);

  @Query("select u from UserEntity u where u.email = :email and u.isActive = true")
  Optional<UserEntity> findActiveByEmail(@Param("email") String email);

  boolean existsByPhoneNumber(String phoneNumber);
```

`customer/infrastructure/repository/JpaUserRepository.java` — add:

```java
  @Override
  public Optional<UserEntity> findByPhoneNumber(String phoneNumber) {
    return jpaDataUserRepository.findActiveByPhoneNumber(phoneNumber);
  }

  @Override
  public Optional<UserEntity> findByEmail(String email) {
    return jpaDataUserRepository.findActiveByEmail(email);
  }

  @Override
  public boolean existsByPhoneNumber(String phoneNumber) {
    return jpaDataUserRepository.existsByPhoneNumber(phoneNumber);
  }
```

### 1.6 Flyway test

`src/test/java/com/example/payment/PaymentApplicationTests.java`: add `"5"` to `containsExactly(...)`.

### ✅ Stage 1 check

Stage 1 alone does not start cleanly yet, because the security starter wants a `JwtDecoder` bean
that Stage 2 creates. Do Stage 2, then run the check there.

---

# Stage 2 — Lock everything, open what is public

### 2.1 Settings

`src/main/resources/application.properties` — add:

```properties
# --- Security ---
# HS256 needs at least 32 bytes. The default is for local development ONLY; production must set JWT_SECRET.
payment.security.jwt.secret=${JWT_SECRET:local-dev-only-secret-change-me-0123456789}
payment.security.jwt.issuer=payment-service
payment.security.jwt.access-token-ttl=15m
payment.security.jwt.refresh-token-ttl=30d
payment.security.login.max-failed-attempts=5
payment.security.login.lock-duration=15m
# Google sign-in is off while this is empty.
payment.security.google.client-id=${GOOGLE_CLIENT_ID:}
# First admin, created at startup if both are set and the phone is not registered yet.
payment.security.bootstrap-admin.phone=${BOOTSTRAP_ADMIN_PHONE:}
payment.security.bootstrap-admin.password=${BOOTSTRAP_ADMIN_PASSWORD:}
```

`shared/security/IdentityProperties.java`

```java
package com.example.payment.shared.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "payment.security")
public class IdentityProperties {
  @Valid private final Jwt jwt = new Jwt();
  @Valid private final Login login = new Login();
  private final Google google = new Google();
  private final BootstrapAdmin bootstrapAdmin = new BootstrapAdmin();

  @Getter
  @Setter
  public static class Jwt {
    @NotBlank
    @Size(min = 32, message = "must be at least 32 characters for HS256")
    private String secret;

    @NotBlank private String issuer = "payment-service";
    @NotNull private Duration accessTokenTtl = Duration.ofMinutes(15);
    @NotNull private Duration refreshTokenTtl = Duration.ofDays(30);
  }

  @Getter
  @Setter
  public static class Login {
    @Min(1)
    private int maxFailedAttempts = 5;

    @NotNull private Duration lockDuration = Duration.ofMinutes(15);
  }

  @Getter
  @Setter
  public static class Google {
    private String clientId = "";
  }

  @Getter
  @Setter
  public static class BootstrapAdmin {
    private String phone = "";
    private String password = "";
  }
}
```

The app refuses to start with a secret shorter than 32 characters. A short secret can be guessed,
and then anyone can forge an admin token.

### 2.2 401 and 403 as JSON

Without this, Spring answers with an empty body. The frontend expects `application/problem+json`
like your other errors.

`shared/security/ProblemDetailSecurityHandler.java`

```java
package com.example.payment.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 401 = "we don't know who you are". 403 = "we know you, and you may not do this". */
@Component
@RequiredArgsConstructor
public class ProblemDetailSecurityHandler implements AuthenticationEntryPoint, AccessDeniedHandler {
  private final JsonMapper jsonMapper;

  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
      throws IOException {
    write(response, HttpStatus.UNAUTHORIZED, "Sign in required.");
  }

  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
      throws IOException {
    write(response, HttpStatus.FORBIDDEN, "You do not have permission to do this.");
  }

  private void write(HttpServletResponse response, HttpStatus status, String detail)
      throws IOException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "about:blank");
    body.put("title", status.getReasonPhrase());
    body.put("status", status.value());
    body.put("detail", detail);

    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    jsonMapper.writeValue(response.getOutputStream(), body);
  }
}
```

The message never says *why* a token was rejected (expired, bad signature…). That detail only helps
an attacker.

### 2.3 The security configuration

`shared/security/SecurityConfiguration.java`

```java
package com.example.payment.shared.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity // turns on @PreAuthorize
@EnableConfigurationProperties(IdentityProperties.class)
public class SecurityConfiguration {

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemDetailSecurityHandler handler)
      throws Exception {
    http
        // CSRF protects cookie logins. We send the token in a header, so it does not apply.
        .csrf(csrf -> csrf.disable())
        // No server session: every request proves itself with its token.
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth
                    // --- public ---
                    .requestMatchers("/auth/**")
                    .permitAll()
                    .requestMatchers("/actuator/health/**")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/product/**", "/products/**")
                    .permitAll()
                    // --- admin only ---
                    .requestMatchers("/admin/**", "/customers/**")
                    .hasRole("ADMIN")
                    .requestMatchers("/product/**", "/products/**") // any non-GET
                    .hasRole("ADMIN")
                    .requestMatchers(HttpMethod.PATCH, "/payments/*/status")
                    .hasRole("ADMIN")
                    // --- everything else: any signed-in user ---
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            oauth ->
                oauth
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                    .authenticationEntryPoint(handler)
                    .accessDeniedHandler(handler))
        .exceptionHandling(
            e -> e.authenticationEntryPoint(handler).accessDeniedHandler(handler));
    return http.build();
  }

  /** Our token has "roles": ["ADMIN"]. Spring's hasRole("ADMIN") looks for "ROLE_ADMIN". */
  private JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
    roles.setAuthoritiesClaimName("roles");
    roles.setAuthorityPrefix("ROLE_");
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(roles);
    return converter;
  }

  @Bean
  SecretKey jwtSecretKey(IdentityProperties properties) {
    byte[] secret = properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
    return new SecretKeySpec(secret, "HmacSHA256");
  }

  /** Signs our access tokens. */
  @Bean
  JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
  }

  /** Verifies incoming access tokens: signature, expiry, and that WE issued them. */
  @Bean
  JwtDecoder jwtDecoder(SecretKey jwtSecretKey, IdentityProperties properties) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
    decoder.setJwtValidator(
        JwtValidators.createDefaultWithIssuer(properties.getJwt().getIssuer()));
    return decoder;
  }

  /** bcrypt with a "{bcrypt}" prefix, so the algorithm can be upgraded later. */
  @Bean
  PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }
}
```

How to read the rules:

- **Order matters. The first match wins.** `GET /product/**` is public; the next `/product/**` line
  catches what is left (POST, PUT, DELETE) and needs ADMIN.
- **`anyRequest().authenticated()` is last.** A new endpoint you forget to list is locked, not open.
- `/product` is today's path and `/products` the one after TASK-002. Listing both means this file
  does not break when the path changes.
- If TASK-002 added Swagger, also permit `"/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"`.
- No CORS setup: the web app calls through the Vite proxy (same origin) and mobile apps do not use CORS.
- The Kafka consumer is not affected. Security filters only run on HTTP requests.

### ✅ Stage 2 check

```powershell
.\mvnw.cmd spotless:apply
.\mvnw.cmd test
docker compose up -d --build
curl.exe -i http://localhost:8081/orders             # 401 + JSON "Sign in required."
curl.exe -i http://localhost:8081/product            # 200 (public)
curl.exe -i http://localhost:8081/actuator/health    # 200
```

| Problem | Cause |
|---|---|
| `No qualifying bean of type JwtDecoder` | `SecurityConfiguration` is not under `com.example.payment` or lacks `@Configuration` |
| `secret must be at least 32 characters` | the property line in 2.1 is missing or `JWT_SECRET` is too short |
| Everything returns 401, even `/product` | rule order changed, or the path has no leading `/` |
| Schema-validation error on `users` | V5 not applied — check the file name (two underscores) |

---

# Stage 3 — Token code

New module: `identity/` with the same layers as `payment/` (`api`, `application`, `domain`, `infrastructure`).

### 3.1 Two exceptions

`shared/application/InvalidCredentialsException.java`

```java
package com.example.payment.shared.application;

/** Becomes HTTP 401. Keep the message vague: never say WHICH part was wrong. */
public class InvalidCredentialsException extends RuntimeException {
  public InvalidCredentialsException(String message) {
    super(message);
  }
}
```

`shared/application/LoginLockedException.java`

```java
package com.example.payment.shared.application;

/** Becomes HTTP 423. */
public class LoginLockedException extends RuntimeException {
  public LoginLockedException(String message) {
    super(message);
  }
}
```

`shared/api/ApiExceptionHandler.java` — add two handlers:

```java
  @ExceptionHandler(InvalidCredentialsException.class)
  public ProblemDetail handleInvalidCredentials(InvalidCredentialsException exception) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, exception.getMessage());
  }

  @ExceptionHandler(LoginLockedException.class)
  public ProblemDetail handleLoginLocked(LoginLockedException exception) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.LOCKED, exception.getMessage());
  }
```

⚠️ Never add a catch-all `@ExceptionHandler(Exception.class)` to this class without re-throwing
`AccessDeniedException`. A catch-all would swallow "403 forbidden" from `@PreAuthorize` and turn it
into a 500.

### 3.2 Access token (JWT)

Port — `identity/application/AccessTokenIssuer.java`

```java
package com.example.payment.identity.application;

import com.example.payment.customer.domain.UserEntity;

public interface AccessTokenIssuer {
  String issue(UserEntity user);
}
```

Implementation — `identity/infrastructure/security/JwtAccessTokenIssuer.java`

```java
package com.example.payment.identity.infrastructure.security;

import com.example.payment.customer.domain.UserEntity;
import com.example.payment.identity.application.AccessTokenIssuer;
import com.example.payment.shared.security.IdentityProperties;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JwtAccessTokenIssuer implements AccessTokenIssuer {
  private final JwtEncoder jwtEncoder;
  private final IdentityProperties properties;

  @Override
  public String issue(UserEntity user) {
    Instant now = Instant.now();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(properties.getJwt().getIssuer())
            .subject(user.getId().toString()) // who
            .issuedAt(now)
            .expiresAt(now.plus(properties.getJwt().getAccessTokenTtl()))
            .id(UUID.randomUUID().toString())
            .claim("roles", user.getRoles().stream().map(Enum::name).sorted().toList())
            .build();
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
    return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }
}
```

A JWT is **signed, not encrypted**. Anyone can read it (paste one into jwt.io). So put only the user
id and roles in it. Never a phone, email or balance.

### 3.3 Refresh token

`identity/domain/RefreshTokenEntity.java`

```java
package com.example.payment.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshTokenEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long userId;

  @Column(nullable = false, length = 64, unique = true)
  private String tokenHash;

  @Column(nullable = false)
  private UUID familyId;

  @Column(nullable = false)
  private LocalDateTime expiresAt;

  private LocalDateTime revokedAt;

  @Column(nullable = false, updatable = false)
  private LocalDateTime createdAt;

  public static RefreshTokenEntity issue(
      Long userId, String tokenHash, UUID familyId, LocalDateTime now, LocalDateTime expiresAt) {
    RefreshTokenEntity token = new RefreshTokenEntity();
    token.userId = userId;
    token.tokenHash = tokenHash;
    token.familyId = familyId;
    token.createdAt = now;
    token.expiresAt = expiresAt;
    return token;
  }

  public boolean isRevoked() {
    return revokedAt != null;
  }

  public boolean isExpired(LocalDateTime now) {
    return !expiresAt.isAfter(now);
  }

  public void revoke(LocalDateTime now) {
    revokedAt = now;
  }
}
```

Port — `identity/application/RefreshTokenRepository.java`

```java
package com.example.payment.identity.application;

import com.example.payment.identity.domain.RefreshTokenEntity;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository {
  RefreshTokenEntity save(RefreshTokenEntity token);

  Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

  /** Revokes every still-valid token created from the same login. */
  void revokeFamily(UUID familyId, LocalDateTime now);
}
```

`identity/infrastructure/repository/JpaDataRefreshTokenRepository.java`

```java
package com.example.payment.identity.infrastructure.repository;

import com.example.payment.identity.domain.RefreshTokenEntity;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaDataRefreshTokenRepository extends JpaRepository<RefreshTokenEntity, Long> {
  Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

  @Modifying
  @Query(
      "update RefreshTokenEntity t set t.revokedAt = :now "
          + "where t.familyId = :familyId and t.revokedAt is null")
  int revokeFamily(@Param("familyId") UUID familyId, @Param("now") LocalDateTime now);
}
```

`identity/infrastructure/repository/JpaRefreshTokenRepository.java`

```java
package com.example.payment.identity.infrastructure.repository;

import com.example.payment.identity.application.RefreshTokenRepository;
import com.example.payment.identity.domain.RefreshTokenEntity;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JpaRefreshTokenRepository implements RefreshTokenRepository {
  private final JpaDataRefreshTokenRepository jpaDataRefreshTokenRepository;

  @Override
  public RefreshTokenEntity save(RefreshTokenEntity token) {
    return jpaDataRefreshTokenRepository.save(token);
  }

  @Override
  public Optional<RefreshTokenEntity> findByTokenHash(String tokenHash) {
    return jpaDataRefreshTokenRepository.findByTokenHash(tokenHash);
  }

  @Override
  public void revokeFamily(UUID familyId, LocalDateTime now) {
    jpaDataRefreshTokenRepository.revokeFamily(familyId, now);
  }
}
```

`identity/application/RefreshTokenService.java`

```java
package com.example.payment.identity.application;

import com.example.payment.identity.domain.RefreshTokenEntity;
import com.example.payment.shared.application.InvalidCredentialsException;
import com.example.payment.shared.security.IdentityProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(noRollbackFor = InvalidCredentialsException.class) // see "Why noRollbackFor" below
public class RefreshTokenService {
  private static final SecureRandom RANDOM = new SecureRandom();

  private final RefreshTokenRepository refreshTokenRepository;
  private final IdentityProperties properties;

  public record Rotation(Long userId, String refreshToken) {}

  /** New login → new family. Returns the raw token; only its hash is stored. */
  public String issue(Long userId) {
    return issue(userId, UUID.randomUUID(), LocalDateTime.now());
  }

  /** Swaps a valid token for a new one. Each token works exactly once. */
  public Rotation rotate(String rawToken) {
    LocalDateTime now = LocalDateTime.now();
    RefreshTokenEntity current =
        refreshTokenRepository.findByTokenHash(sha256(rawToken)).orElseThrow(this::expired);

    if (current.isRevoked()) {
      // An already-used token came back: someone copied it. Kill the whole login.
      refreshTokenRepository.revokeFamily(current.getFamilyId(), now);
      throw expired();
    }
    if (current.isExpired(now)) {
      throw expired();
    }

    current.revoke(now);
    return new Rotation(current.getUserId(), issue(current.getUserId(), current.getFamilyId(), now));
  }

  /** Logout: ends this login on this device. Unknown tokens are ignored. */
  public void revoke(String rawToken) {
    refreshTokenRepository
        .findByTokenHash(sha256(rawToken))
        .ifPresent(
            token -> refreshTokenRepository.revokeFamily(token.getFamilyId(), LocalDateTime.now()));
  }

  private String issue(Long userId, UUID familyId, LocalDateTime now) {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    refreshTokenRepository.save(
        RefreshTokenEntity.issue(
            userId,
            sha256(rawToken),
            familyId,
            now,
            now.plus(properties.getJwt().getRefreshTokenTtl())));
    return rawToken;
  }

  private InvalidCredentialsException expired() {
    return new InvalidCredentialsException("Session expired.");
  }

  static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is always available", e);
    }
  }
}
```

**Rotation and theft detection.** Each refresh gives a new token and kills the old one. If a thief
copies a token, either the thief or the real user uses it first. When the other one tries the
now-dead token, we see a revoked token coming back and cancel the whole family. Both are signed
out, and the real user logs in again.

**Why `noRollbackFor`.** By default Spring rolls the transaction back when a `RuntimeException`
leaves the method. Here we *want* to keep a change and still throw: "revoke the family, then answer
401". Without `noRollbackFor`, the revocation would be undone. The same applies to the
wrong-password counter in Stage 4.

**Why plain SHA-256, not bcrypt, for this token.** bcrypt is slow on purpose because passwords are
short and guessable. This token is 256 random bits; nobody can guess it, so a fast hash is enough.

### ✅ Stage 3 check

`.\mvnw.cmd spotless:apply` then `.\mvnw.cmd test`. The app still starts; no new endpoint yet.

---

# Stage 4 — Auth endpoints

### 4.1 Results

`identity/application/dto/UserProfile.java`

```java
package com.example.payment.identity.application.dto;

import com.example.payment.customer.domain.Role;
import com.example.payment.customer.domain.UserEntity;
import com.example.payment.customer.domain.UserStatus;
import java.util.Set;

public record UserProfile(
    Long id, String phone, String email, String fullName, Set<Role> roles, UserStatus status) {
  public static UserProfile from(UserEntity user) {
    return new UserProfile(
        user.getId(),
        user.getPhoneNumber(),
        user.getEmail(),
        user.fullName(),
        Set.copyOf(user.getRoles()),
        user.getStatus());
  }
}
```

`identity/application/dto/AuthResult.java`

```java
package com.example.payment.identity.application.dto;

public record AuthResult(String accessToken, String refreshToken, UserProfile user) {}
```

### 4.2 The service

`identity/application/AuthService.java`

```java
package com.example.payment.identity.application;

import com.example.payment.identity.application.dto.AuthResult;
import com.example.payment.identity.application.dto.UserProfile;

public interface AuthService {
  AuthResult register(String phone, String password);

  AuthResult login(String login, String password);

  AuthResult refresh(String refreshToken);

  void logout(String refreshToken);

  UserProfile me(Long userId);
}
```

`identity/application/AuthServiceImpl.java`

```java
package com.example.payment.identity.application;

import com.example.payment.customer.application.CustomerRepository;
import com.example.payment.customer.domain.Role;
import com.example.payment.customer.domain.UserEntity;
import com.example.payment.customer.domain.UserStatus;
import com.example.payment.identity.application.dto.AuthResult;
import com.example.payment.identity.application.dto.UserProfile;
import com.example.payment.shared.application.ConflictException;
import com.example.payment.shared.application.InvalidCredentialsException;
import com.example.payment.shared.application.LoginLockedException;
import com.example.payment.shared.security.IdentityProperties;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(noRollbackFor = {InvalidCredentialsException.class, LoginLockedException.class})
public class AuthServiceImpl implements AuthService {
  private static final String BAD_LOGIN = "Invalid credentials.";

  private final CustomerRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final AccessTokenIssuer accessTokenIssuer;
  private final RefreshTokenService refreshTokenService;
  private final IdentityProperties properties;

  @Override
  public AuthResult register(String phone, String password) {
    if (userRepository.existsByPhoneNumber(phone)) {
      throw new ConflictException("Phone already registered.");
    }
    UserEntity user = new UserEntity();
    user.setPhoneNumber(phone);
    user.setPasswordHash(passwordEncoder.encode(password));
    user.getRoles().add(Role.SHOPPER); // never take the role from the request
    return issueTokens(userRepository.save(user));
  }

  @Override
  public AuthResult login(String login, String password) {
    UserEntity user = findByLogin(login).orElseThrow(() -> new InvalidCredentialsException(BAD_LOGIN));

    LocalDateTime now = LocalDateTime.now();
    if (user.isLocked(now)) {
      throw new LoginLockedException("Account locked. Try again in 15 minutes.");
    }

    boolean passwordMatches =
        user.getPasswordHash() != null // Google-only accounts have no password
            && passwordEncoder.matches(password, user.getPasswordHash());
    if (!passwordMatches || user.getStatus() != UserStatus.ACTIVE) {
      user.recordFailedLogin(
          now,
          properties.getLogin().getMaxFailedAttempts(),
          properties.getLogin().getLockDuration());
      throw new InvalidCredentialsException(BAD_LOGIN); // counter is kept: noRollbackFor
    }

    user.recordSuccessfulLogin();
    return issueTokens(user);
  }

  @Override
  public AuthResult refresh(String refreshToken) {
    RefreshTokenService.Rotation rotation = refreshTokenService.rotate(refreshToken);
    UserEntity user =
        userRepository
            .findById(rotation.userId())
            .filter(found -> found.getStatus() == UserStatus.ACTIVE)
            .orElseThrow(() -> new InvalidCredentialsException("Session expired."));
    // Roles are read again here, so a role change reaches the user within one refresh.
    return new AuthResult(
        accessTokenIssuer.issue(user), rotation.refreshToken(), UserProfile.from(user));
  }

  @Override
  public void logout(String refreshToken) {
    refreshTokenService.revoke(refreshToken);
  }

  @Override
  @Transactional(readOnly = true)
  public UserProfile me(Long userId) {
    return userRepository
        .findById(userId)
        .map(UserProfile::from)
        .orElseThrow(() -> new InvalidCredentialsException("Sign in required."));
  }

  // Package-private: Stage 6 (Google) reuses it.
  AuthResult issueTokens(UserEntity user) {
    return new AuthResult(
        accessTokenIssuer.issue(user),
        refreshTokenService.issue(user.getId()),
        UserProfile.from(user));
  }

  private Optional<UserEntity> findByLogin(String login) {
    String value = login.trim();
    return value.contains("@")
        ? userRepository.findByEmail(value.toLowerCase(Locale.ROOT))
        : userRepository.findByPhoneNumber(value);
  }
}
```

Security rules hidden in this class:

1. **Same message for "no such user" and "wrong password".** Different messages let an attacker
   find out which phone numbers have accounts.
2. **The role is set by the server.** If `register` accepted `roles` from the body, anyone could
   register as ADMIN.
3. **Never log the password or the tokens.**
4. Two people registering the same phone at the same instant: the unique constraint stops the
   second one, and your existing `DataIntegrityViolationException` handler answers 409.

### 4.3 Requests and responses

`identity/api/request/RegisterRequest.java`

```java
package com.example.payment.identity.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @NotBlank @Pattern(regexp = "^0\\d{9}$", message = "must be a 10-digit phone starting with 0")
        String phone,
    @NotBlank @Size(min = 8, max = 72, message = "must be 8 to 72 characters") String password) {}
```

(72 is bcrypt's limit; it ignores anything longer.)

`identity/api/request/LoginRequest.java`

```java
package com.example.payment.identity.api.request;

import jakarta.validation.constraints.NotBlank;

/** {@code login} is a phone number or an email. */
public record LoginRequest(@NotBlank String login, @NotBlank String password) {}
```

`identity/api/request/RefreshTokenRequest.java`

```java
package com.example.payment.identity.api.request;

import jakarta.validation.constraints.NotBlank;

public record RefreshTokenRequest(@NotBlank String refreshToken) {}
```

`identity/api/response/UserResponse.java`

```java
package com.example.payment.identity.api.response;

import com.example.payment.customer.domain.Role;
import com.example.payment.identity.application.dto.UserProfile;
import java.util.Set;

/** Same shape as the frontend's {@code User} type. */
public record UserResponse(
    String id,
    String phone,
    String email,
    String fullName,
    Set<Role> roles,
    int kycTier,
    String status) {
  public static UserResponse from(UserProfile user) {
    return new UserResponse(
        user.id().toString(),
        user.phone(),
        user.email(),
        user.fullName(),
        user.roles(),
        0, // KYC arrives with the wallet (P1 banking)
        user.status().name());
  }
}
```

`identity/api/response/TokensResponse.java`

```java
package com.example.payment.identity.api.response;

import com.example.payment.identity.application.dto.AuthResult;

public record TokensResponse(String accessToken, String refreshToken, UserResponse user) {
  public static TokensResponse from(AuthResult result) {
    return new TokensResponse(
        result.accessToken(), result.refreshToken(), UserResponse.from(result.user()));
  }
}
```

### 4.4 Who is calling?

`shared/security/CurrentUser.java`

```java
package com.example.payment.shared.security;

import java.util.Set;
import org.springframework.security.oauth2.jwt.Jwt;

/** The signed-in user, read from the verified access token. */
public record CurrentUser(Long id, Set<String> roles) {
  private static final Set<String> STAFF_ROLES = Set.of("ADMIN", "FINANCE", "SUPPORT");

  public static CurrentUser from(Jwt jwt) {
    return new CurrentUser(
        Long.valueOf(jwt.getSubject()), Set.copyOf(jwt.getClaimAsStringList("roles")));
  }

  public boolean isStaff() {
    return roles.stream().anyMatch(STAFF_ROLES::contains);
  }
}
```

### 4.5 Controllers

`identity/api/AuthController.java`

```java
package com.example.payment.identity.api;

import com.example.payment.identity.api.request.LoginRequest;
import com.example.payment.identity.api.request.RefreshTokenRequest;
import com.example.payment.identity.api.request.RegisterRequest;
import com.example.payment.identity.api.response.TokensResponse;
import com.example.payment.identity.application.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
  private final AuthService authService;

  @PostMapping("/register")
  public ResponseEntity<TokensResponse> register(@Valid @RequestBody RegisterRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(TokensResponse.from(authService.register(request.phone(), request.password())));
  }

  @PostMapping("/login")
  public ResponseEntity<TokensResponse> login(@Valid @RequestBody LoginRequest request) {
    return ResponseEntity.ok(
        TokensResponse.from(authService.login(request.login(), request.password())));
  }

  @PostMapping("/refresh")
  public ResponseEntity<TokensResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
    return ResponseEntity.ok(TokensResponse.from(authService.refresh(request.refreshToken())));
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
    authService.logout(request.refreshToken());
    return ResponseEntity.noContent().build();
  }
}
```

`identity/api/MeController.java`

```java
package com.example.payment.identity.api;

import com.example.payment.identity.api.response.UserResponse;
import com.example.payment.identity.application.AuthService;
import com.example.payment.shared.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/me")
@RequiredArgsConstructor
public class MeController {
  private final AuthService authService;

  @GetMapping
  public ResponseEntity<UserResponse> me(@AuthenticationPrincipal Jwt jwt) {
    return ResponseEntity.ok(UserResponse.from(authService.me(CurrentUser.from(jwt).id())));
  }
}
```

`@AuthenticationPrincipal Jwt jwt` is the token Spring already verified. The user id comes from the
token, **never from a path or body parameter**. That is what stops user A from asking for user B.

### ✅ Stage 4 check (Git Bash)

```bash
API=http://localhost:8081
curl -s -X POST $API/auth/register -H 'Content-Type: application/json' \
  -d '{"phone":"0900000001","password":"secret-123"}'
# → 201 { "accessToken": "eyJ...", "refreshToken": "...", "user": { "roles": ["SHOPPER"], ... } }

ACCESS=<paste accessToken>;  REFRESH=<paste refreshToken>

curl -s $API/me -H "Authorization: Bearer $ACCESS"                 # 200, your user
curl -s -i $API/me                                                # 401
curl -s -X POST $API/auth/refresh -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$REFRESH\"}"                             # 200, NEW pair
curl -s -i -X POST $API/auth/refresh -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$REFRESH\"}"                             # 401: old token is dead,
                                                                   #      and the new one is revoked too
```

Then type a wrong password 5 times on `/auth/login`: attempts 1–5 answer 401, attempt 6 answers
**423**. In the DB: `select failed_login_count, locked_until from users;`.

---

# Stage 5 — Permissions: admin vs user

There are **two different questions**. Most real bugs come from answering only the first.

| Question | Example | Tool |
|---|---|---|
| **Role:** may this *kind* of user call this endpoint? | Only ADMIN may list customers | URL rules (Stage 2) or `@PreAuthorize` |
| **Ownership:** may *this* user touch *this* row? | A shopper may read only their own orders | A check in the service |

### 5.1 The first admin

Nobody can promote the first admin through the API, so create one at startup from environment
variables.

`identity/infrastructure/BootstrapAdminRunner.java`

```java
package com.example.payment.identity.infrastructure;

import com.example.payment.customer.application.CustomerRepository;
import com.example.payment.customer.domain.Role;
import com.example.payment.customer.domain.UserEntity;
import com.example.payment.shared.security.IdentityProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class BootstrapAdminRunner implements ApplicationRunner {
  private final CustomerRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final IdentityProperties properties;

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    String phone = properties.getBootstrapAdmin().getPhone();
    String password = properties.getBootstrapAdmin().getPassword();
    if (phone.isBlank() || password.isBlank() || userRepository.existsByPhoneNumber(phone)) {
      return;
    }
    UserEntity admin = new UserEntity();
    admin.setPhoneNumber(phone);
    admin.setFirstName("Admin");
    admin.setPasswordHash(passwordEncoder.encode(password));
    admin.getRoles().add(Role.ADMIN);
    userRepository.save(admin);
    log.info("Bootstrap admin created for phone ending {}", phone.substring(phone.length() - 3));
  }
}
```

`docker-compose.yml` → `payment-local.environment` (local values only):

```yaml
      BOOTSTRAP_ADMIN_PHONE: "0999999999"
      BOOTSTRAP_ADMIN_PASSWORD: "admin-local-123"
```

### 5.2 Role check on a method: `@PreAuthorize`

URL rules live in one file and are easy to audit. `@PreAuthorize` sits next to the code it protects.
Use URL rules for whole areas (`/admin/**`) and `@PreAuthorize` for single methods. Example — an
admin changes a user's roles.

Add to `AuthService`:

```java
  UserProfile setRoles(Long userId, java.util.Set<com.example.payment.customer.domain.Role> roles);
```

Add to `AuthServiceImpl` (plus imports for `Set`, `ResourceNotFoundException`):

```java
  @Override
  public UserProfile setRoles(Long userId, Set<Role> roles) {
    UserEntity user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    user.getRoles().clear();
    user.getRoles().addAll(roles);
    return UserProfile.from(user);
  }
```

`identity/api/request/SetRolesRequest.java`

```java
package com.example.payment.identity.api.request;

import com.example.payment.customer.domain.Role;
import jakarta.validation.constraints.NotEmpty;
import java.util.Set;

public record SetRolesRequest(@NotEmpty Set<Role> roles) {}
```

`identity/api/AdminUserController.java`

```java
package com.example.payment.identity.api;

import com.example.payment.identity.api.request.SetRolesRequest;
import com.example.payment.identity.api.response.UserResponse;
import com.example.payment.identity.application.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class AdminUserController {
  private final AuthService authService;

  @PutMapping("/{id}/roles")
  @PreAuthorize("hasRole('ADMIN')") // second lock; "/admin/**" in the URL rules is the first
  public ResponseEntity<UserResponse> setRoles(
      @PathVariable Long id, @Valid @RequestBody SetRolesRequest request) {
    return ResponseEntity.ok(UserResponse.from(authService.setRoles(id, request.roles())));
  }
}
```

A role change reaches the user at their next refresh (at most 15 minutes), because roles are inside
the access token. That delay is the price of not hitting the DB on every request.

### 5.3 Ownership: "only my own orders"

This is not a new file. It is a rule to apply to **every** endpoint that returns user data
(orders, payments, later the wallet). Pattern:

**Controller** — pass the caller down:

```java
  @GetMapping("/{id}")
  public ResponseEntity<OrderResponse> getOrder(
      @PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
    return ResponseEntity.ok(toResponse(orderService.getOrderById(id, CurrentUser.from(jwt))));
  }
```

**Service** — check after loading:

```java
  public OrderView getOrderById(Long id, CurrentUser caller) {
    OrderEntity order = getOrder(id);
    if (!caller.isStaff() && !order.getUser().getId().equals(caller.id())) {
      // 404, not 403: a 403 would confirm that order 123 exists.
      throw new ResourceNotFoundException("Order", id);
    }
    return OrderView.from(order);
  }
```

Apply the same idea to:

| Endpoint | Rule |
|---|---|
| `GET /orders`, `GET /payments` (lists) | staff: all rows. Shopper: add `where customer_id = :callerId` in the query. Do not load all and filter in Java |
| `POST /orders` | Ignore `customerId` from the body for shoppers; use `caller.id()`. Only staff may create for someone else |
| `POST /payments` | The order must belong to the caller |
| `GET /orders/{id}`, `GET /payments/{id}` | owner or staff, else 404 |

This bug class is called IDOR (changing an id in the URL to read someone else's data). Role checks
do not catch it; a logged-in shopper passes every role check.

### ✅ Stage 5 check

```bash
# shopper token from Stage 4
curl -s -i $API/customers -H "Authorization: Bearer $ACCESS"            # 403
# admin
curl -s -X POST $API/auth/login -H 'Content-Type: application/json' \
  -d '{"login":"0999999999","password":"admin-local-123"}'
ADMIN=<paste accessToken>
curl -s -i $API/customers -H "Authorization: Bearer $ADMIN"             # 200
curl -s -X PUT $API/admin/users/1/roles -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' -d '{"roles":["SHOPPER","SUPPORT"]}'   # 200
# ownership: shopper A asks for shopper B's order → 404
```

---

# Stage 6 — Sign in with Google

### How it works

```text
 App (web/mobile)              Google                     Our backend
      │  "Sign in with Google"   │                             │
      ├─────────────────────────►│                             │
      │◄──── ID token (a JWT signed by Google) ─────────────────┤
      │                                                        │
      ├── POST /auth/social/google { idToken } ───────────────►│
      │                              verify: Google's signature │
      │                                      issuer = Google    │
      │                                      audience = OUR app │
      │                                      not expired        │
      │                              find or create the user    │
      │◄── { accessToken, refreshToken, user }  (OUR tokens) ───┤
```

After this call, Google is out of the picture. Every request uses our own tokens, exactly like a
password login. So Stages 2–5 need no change.

**The audience check is the important one.** Any website can get a valid Google token for your
user. Only tokens whose `aud` is *our* client id were issued for *our* app. Skip that check and a
malicious site can log in to our backend as anyone who signed in there.

### 6.1 Get a client id (once)

Google Cloud Console → APIs & Services → Credentials → **Create credentials → OAuth client ID** →
type *Web application* → Authorized JavaScript origin `http://localhost:5173`. Copy the client id
(`…apps.googleusercontent.com`) and set `GOOGLE_CLIENT_ID` (in `docker-compose.yml` or your run
configuration). It is an identifier, not a secret. This flow needs no client secret.

### 6.2 Verifier

Port — `identity/application/GoogleIdTokenVerifier.java`

```java
package com.example.payment.identity.application;

public interface GoogleIdTokenVerifier {
  /** Throws InvalidCredentialsException if the token is not a valid Google token for our app. */
  GoogleUser verify(String idToken);

  record GoogleUser(
      String subject, String email, boolean emailVerified, String givenName, String familyName) {}
}
```

Implementation — `identity/infrastructure/security/NimbusGoogleIdTokenVerifier.java`

```java
package com.example.payment.identity.infrastructure.security;

import com.example.payment.identity.application.GoogleIdTokenVerifier;
import com.example.payment.shared.application.InvalidCredentialsException;
import com.example.payment.shared.security.IdentityProperties;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class NimbusGoogleIdTokenVerifier implements GoogleIdTokenVerifier {
  private static final String GOOGLE_KEYS = "https://www.googleapis.com/oauth2/v3/certs";
  private static final Set<String> GOOGLE_ISSUERS =
      Set.of("https://accounts.google.com", "accounts.google.com");

  private final String clientId;
  // NOT a Spring bean on purpose: a second JwtDecoder bean would confuse the resource server,
  // which must only ever use OUR decoder for the Authorization header.
  private final NimbusJwtDecoder googleDecoder;

  public NimbusGoogleIdTokenVerifier(IdentityProperties properties) {
    this.clientId = properties.getGoogle().getClientId();
    this.googleDecoder = NimbusJwtDecoder.withJwkSetUri(GOOGLE_KEYS).build(); // keys fetched on first use
    OAuth2TokenValidator<Jwt> issuerAndAudience = this::validateIssuerAndAudience;
    this.googleDecoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(), issuerAndAudience));
  }

  @Override
  public GoogleUser verify(String idToken) {
    if (clientId == null || clientId.isBlank()) {
      throw new InvalidCredentialsException("Google sign-in is not available.");
    }
    try {
      Jwt jwt = googleDecoder.decode(idToken);
      return new GoogleUser(
          jwt.getSubject(),
          jwt.getClaimAsString("email"),
          Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified")),
          jwt.getClaimAsString("given_name"),
          jwt.getClaimAsString("family_name"));
    } catch (JwtException exception) {
      log.info("Rejected Google ID token: {}", exception.getMessage());
      throw new InvalidCredentialsException("Google sign-in failed.");
    }
  }

  private OAuth2TokenValidatorResult validateIssuerAndAudience(Jwt jwt) {
    boolean issuerOk = GOOGLE_ISSUERS.contains(jwt.getClaimAsString("iss"));
    boolean audienceOk = jwt.getAudience() != null && jwt.getAudience().contains(clientId);
    return issuerOk && audienceOk
        ? OAuth2TokenValidatorResult.success()
        : OAuth2TokenValidatorResult.failure(
            new OAuth2Error("invalid_token", "Wrong issuer or audience", null));
  }
}
```

### 6.3 Link a Google account to a user

`identity/domain/SocialIdentityEntity.java`

```java
package com.example.payment.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "social_identities")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SocialIdentityEntity {
  public static final String GOOGLE = "GOOGLE";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long userId;

  @Column(nullable = false, length = 32)
  private String provider;

  @Column(nullable = false)
  private String subject;

  @Column(nullable = false, updatable = false)
  private LocalDateTime createdAt;

  public static SocialIdentityEntity google(Long userId, String subject) {
    SocialIdentityEntity identity = new SocialIdentityEntity();
    identity.userId = userId;
    identity.provider = GOOGLE;
    identity.subject = subject;
    identity.createdAt = LocalDateTime.now();
    return identity;
  }
}
```

Port — `identity/application/SocialIdentityRepository.java`

```java
package com.example.payment.identity.application;

import com.example.payment.identity.domain.SocialIdentityEntity;
import java.util.Optional;

public interface SocialIdentityRepository {
  Optional<SocialIdentityEntity> find(String provider, String subject);

  SocialIdentityEntity save(SocialIdentityEntity identity);
}
```

`identity/infrastructure/repository/JpaDataSocialIdentityRepository.java`

```java
package com.example.payment.identity.infrastructure.repository;

import com.example.payment.identity.domain.SocialIdentityEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaDataSocialIdentityRepository
    extends JpaRepository<SocialIdentityEntity, Long> {
  Optional<SocialIdentityEntity> findByProviderAndSubject(String provider, String subject);
}
```

`identity/infrastructure/repository/JpaSocialIdentityRepository.java`

```java
package com.example.payment.identity.infrastructure.repository;

import com.example.payment.identity.application.SocialIdentityRepository;
import com.example.payment.identity.domain.SocialIdentityEntity;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JpaSocialIdentityRepository implements SocialIdentityRepository {
  private final JpaDataSocialIdentityRepository jpaDataSocialIdentityRepository;

  @Override
  public Optional<SocialIdentityEntity> find(String provider, String subject) {
    return jpaDataSocialIdentityRepository.findByProviderAndSubject(provider, subject);
  }

  @Override
  public SocialIdentityEntity save(SocialIdentityEntity identity) {
    return jpaDataSocialIdentityRepository.save(identity);
  }
}
```

### 6.4 The login logic

Add to `AuthService`:

```java
  AuthResult loginWithGoogle(String idToken);
```

Add two fields to `AuthServiceImpl` (Lombok adds them to the constructor):

```java
  private final GoogleIdTokenVerifier googleIdTokenVerifier;
  private final SocialIdentityRepository socialIdentityRepository;
```

Add the method (plus the import for `SocialIdentityEntity`):

```java
  @Override
  public AuthResult loginWithGoogle(String idToken) {
    GoogleIdTokenVerifier.GoogleUser google = googleIdTokenVerifier.verify(idToken);

    // 1) Returning Google user: matched by Google's permanent id, never by email.
    Optional<SocialIdentityEntity> known =
        socialIdentityRepository.find(SocialIdentityEntity.GOOGLE, google.subject());
    if (known.isPresent()) {
      UserEntity user =
          userRepository
              .findById(known.get().getUserId())
              .filter(found -> found.getStatus() == UserStatus.ACTIVE)
              .orElseThrow(() -> new InvalidCredentialsException("Google sign-in failed."));
      return issueTokens(user);
    }

    // 2) First time. We need an email that Google has verified.
    if (google.email() == null || !google.emailVerified()) {
      throw new InvalidCredentialsException("Google sign-in failed.");
    }
    String email = google.email().toLowerCase(Locale.ROOT);

    // 3) Never silently merge into an existing account (see the note below).
    if (userRepository.existsByEmail(email)) {
      throw new ConflictException(
          "An account with this email already exists. Sign in with your password.");
    }

    // 4) New user.
    UserEntity user = new UserEntity();
    user.setEmail(email);
    user.setFirstName(google.givenName());
    user.setLastName(google.familyName());
    user.getRoles().add(Role.SHOPPER);
    user = userRepository.save(user);
    socialIdentityRepository.save(SocialIdentityEntity.google(user.getId(), google.subject()));
    return issueTokens(user);
  }
```

**Why step 3 refuses to merge.** We do not verify emails yet. An attacker could register first with
the victim's email and a password they know. If Google login then merged into that account, the
victim would be using an account the attacker can also open. The safe rule until email verification
exists: no automatic linking. A later "Link Google" button on the Account page (while signed in)
adds the row in `social_identities` on purpose.

**Why match on `sub`, not email.** A Google email can change or be reassigned. `sub` never changes.

### 6.5 Endpoint

`identity/api/request/GoogleLoginRequest.java`

```java
package com.example.payment.identity.api.request;

import jakarta.validation.constraints.NotBlank;

public record GoogleLoginRequest(@NotBlank String idToken) {}
```

Add to `AuthController` (already public through `/auth/**`):

```java
  @PostMapping("/social/google")
  public ResponseEntity<TokensResponse> loginWithGoogle(
      @Valid @RequestBody GoogleLoginRequest request) {
    return ResponseEntity.ok(TokensResponse.from(authService.loginWithGoogle(request.idToken())));
  }
```

### ✅ Stage 6 check

The frontend has no Google button yet. To get a real ID token for testing, save this as
`google-test.html`, serve it from `http://localhost:5173` (the origin you authorized), sign in, and
copy the token it prints:

```html
<script src="https://accounts.google.com/gsi/client" async></script>
<div id="g_id_onload" data-client_id="YOUR_CLIENT_ID" data-callback="onToken"></div>
<div class="g_id_signin"></div>
<pre id="out"></pre>
<script>function onToken(r){document.getElementById('out').textContent=r.credential}</script>
```

```bash
curl -s -X POST $API/auth/social/google -H 'Content-Type: application/json' \
  -d '{"idToken":"<paste>"}'          # 200 + our tokens; a row in users and in social_identities
```

- Same call again → 200, same user id (no second user).
- Change one character of the token → 401.
- Unset `GOOGLE_CLIENT_ID` → 401 "Google sign-in is not available."

---

# Stage 7 — Tests

### 7.1 Lockout rule (plain unit test)

`src/test/java/com/example/payment/customer/domain/UserEntityTest.java`

```java
package com.example.payment.customer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class UserEntityTest {
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 1, 12, 0);
  private static final Duration LOCK = Duration.ofMinutes(15);

  @Test
  void fifthWrongPasswordLocksForFifteenMinutes() {
    UserEntity user = new UserEntity();

    for (int attempt = 1; attempt <= 4; attempt++) {
      user.recordFailedLogin(NOW, 5, LOCK);
    }
    assertThat(user.isLocked(NOW)).isFalse();

    user.recordFailedLogin(NOW, 5, LOCK);

    assertThat(user.isLocked(NOW)).isTrue();
    assertThat(user.isLocked(NOW.plusMinutes(14))).isTrue();
    assertThat(user.isLocked(NOW.plusMinutes(15))).isFalse();
  }

  @Test
  void successfulLoginClearsTheCounter() {
    UserEntity user = new UserEntity();
    user.recordFailedLogin(NOW, 5, LOCK);

    user.recordSuccessfulLogin();

    assertThat(user.getFailedLoginCount()).isZero();
  }
}
```

### 7.2 Refresh-token rotation

`src/test/java/com/example/payment/identity/application/RefreshTokenServiceTest.java`

```java
package com.example.payment.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.payment.identity.domain.RefreshTokenEntity;
import com.example.payment.shared.application.InvalidCredentialsException;
import com.example.payment.shared.security.IdentityProperties;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {
  @Mock private RefreshTokenRepository repository;
  private RefreshTokenService service;
  private final UUID family = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service = new RefreshTokenService(repository, new IdentityProperties());
  }

  @Test
  void issueStoresOnlyTheHash() {
    String raw = service.issue(7L);

    ArgumentCaptor<RefreshTokenEntity> saved = ArgumentCaptor.forClass(RefreshTokenEntity.class);
    verify(repository).save(saved.capture());
    assertThat(saved.getValue().getTokenHash())
        .isEqualTo(RefreshTokenService.sha256(raw))
        .isNotEqualTo(raw);
  }

  @Test
  void rotateRevokesOldTokenAndKeepsTheFamily() {
    RefreshTokenEntity current = token("old", LocalDateTime.now().plusDays(1));
    when(repository.findByTokenHash(RefreshTokenService.sha256("old")))
        .thenReturn(Optional.of(current));

    RefreshTokenService.Rotation rotation = service.rotate("old");

    assertThat(current.isRevoked()).isTrue();
    assertThat(rotation.userId()).isEqualTo(7L);
    assertThat(rotation.refreshToken()).isNotEqualTo("old");
    ArgumentCaptor<RefreshTokenEntity> saved = ArgumentCaptor.forClass(RefreshTokenEntity.class);
    verify(repository).save(saved.capture());
    assertThat(saved.getValue().getFamilyId()).isEqualTo(family);
  }

  @Test
  void reusingARevokedTokenRevokesTheWholeFamily() {
    RefreshTokenEntity stolen = token("old", LocalDateTime.now().plusDays(1));
    stolen.revoke(LocalDateTime.now());
    when(repository.findByTokenHash(RefreshTokenService.sha256("old")))
        .thenReturn(Optional.of(stolen));

    assertThatThrownBy(() -> service.rotate("old")).isInstanceOf(InvalidCredentialsException.class);

    verify(repository).revokeFamily(eq(family), any());
    verify(repository, never()).save(any());
  }

  @Test
  void expiredTokenIsRejected() {
    when(repository.findByTokenHash(RefreshTokenService.sha256("old")))
        .thenReturn(Optional.of(token("old", LocalDateTime.now().minusSeconds(1))));

    assertThatThrownBy(() -> service.rotate("old")).isInstanceOf(InvalidCredentialsException.class);
  }

  private RefreshTokenEntity token(String raw, LocalDateTime expiresAt) {
    return RefreshTokenEntity.issue(
        7L, RefreshTokenService.sha256(raw), family, LocalDateTime.now().minusHours(1), expiresAt);
  }
}
```

### 7.3 The rules, through real HTTP handling

`src/test/java/com/example/payment/shared/security/SecurityRulesTest.java`

```java
package com.example.payment.shared.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.payment.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SecurityRulesTest {
  @Autowired private MockMvc mockMvc;

  @Test
  void noTokenIs401WithProblemJson() throws Exception {
    mockMvc
        .perform(get("/orders"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Sign in required."));
  }

  @Test
  void shopperCannotListCustomers() throws Exception {
    mockMvc
        .perform(get("/customers").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SHOPPER"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminCanListCustomers() throws Exception {
    mockMvc
        .perform(get("/customers").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk());
  }

  @Test
  void productListIsPublic() throws Exception {
    mockMvc.perform(get("/product")).andExpect(status().isOk());
  }
}
```

- `jwt()` pretends a verified token arrived, so these tests check the **rules**, not the signature.
- In Spring Boot 4, `@AutoConfigureMockMvc` moved to `org.springframework.boot.webmvc.test.autoconfigure`.
  If the import is red, let the IDE find the class.
- After TASK-002, change `/product` to `/products` in the last test.

### Run

```powershell
.\mvnw.cmd spotless:apply
.\mvnw.cmd spotless:check test
```

---

## Done checklist

- [ ] No token → 401 JSON; wrong role → 403 JSON
- [ ] Register, login, `/me`, refresh, logout work with curl
- [ ] Old refresh token is rejected, and reusing it signs the whole login out
- [ ] 5 wrong passwords → 423 for 15 minutes
- [ ] Shopper cannot call admin endpoints; admin can
- [ ] Shopper A cannot read shopper B's order or payment (404)
- [ ] Google token for our client id → our tokens; any other token → 401
- [ ] `password_hash` and tokens never appear in a response or a log line
- [ ] Production sets `JWT_SECRET` (the default in `application.properties` is local-only)

## Not in this guide (next)

| Item | Why it matters |
|---|---|
| SMS OTP (sim) on register and new device, forgot/reset password | The frontend's register flow expects it (IAM-01, IAM-03) |
| Rate limiting on `/auth/*` per IP | Lockout protects one account; rate limiting stops one attacker trying many accounts (IAM-09) |
| "Link Google" while signed in | Safe way to attach Google to a password account |
| Step-up OTP for big transfers | Business rule: transfers > 2,000,000 ₫ (IAM-07) |
| RS256 keys instead of one shared secret | Needed when another service must verify tokens without being able to create them |
| Audit log of staff logins and role changes | Banking requirement in P1 |
