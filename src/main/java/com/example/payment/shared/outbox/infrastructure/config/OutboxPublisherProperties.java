package com.example.payment.shared.outbox.infrastructure.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "payment.outbox.publisher")
public class OutboxPublisherProperties {
  private boolean enabled = false;

  @NotNull private Duration fixedDelay = Duration.ofSeconds(1);

  @Min(1)
  private int batchSize = 50;

  @Min(1)
  private int maxBatchesPerRun = 10;

  @NotNull private Duration leaseDuration = Duration.ofSeconds(30);

  @NotNull private Duration sendTimeout = Duration.ofSeconds(10);

  @Min(1)
  private int maxAttempts = 10;

  @NotNull private Duration initialBackoff = Duration.ofSeconds(1);

  @NotNull private Duration maxBackoff = Duration.ofMinutes(5);

  @AssertTrue(
      message =
          "fixedDelay, leaseDuration, sendTimeout and backoff durations must be positive; "
              + "leaseDuration must exceed sendTimeout; initialBackoff must not exceed maxBackoff")
  public boolean isValidTimingConfiguration() {
    return isPositive(fixedDelay)
        && isPositive(leaseDuration)
        && isPositive(sendTimeout)
        && isPositive(initialBackoff)
        && isPositive(maxBackoff)
        && leaseDuration.compareTo(sendTimeout) > 0
        && initialBackoff.compareTo(maxBackoff) <= 0;
  }

  private boolean isPositive(Duration value) {
    return value != null && value.toMillis() > 0;
  }
}
