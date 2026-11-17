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

@Slf4j
@Component
public class StubPaymentProvider implements PaymentProvider {

  private final Map<String, PaymentProviderResult> resultsByKey = new ConcurrentHashMap<>();
  private final Duration latency;
  private final BigDecimal declinedAbove;

  public StubPaymentProvider(
      @Value("${payment.provider.stub.latency:2s}") Duration latency,
      @Value("${payment.provider.stub.decline-above:10000000}") BigDecimal declinedAbove) {
    this.latency = latency;
    this.declinedAbove = declinedAbove;
  }

  @Override
  public PaymentProviderResult charge(
      Long paymentId, BigDecimal amount, PaymentMethod method, String idempotencyKey) {
    PaymentProviderResult previous = resultsByKey.get(idempotencyKey);
    if (previous != null) {
      log.info("Stub provider: replay of key {} -> {}", idempotencyKey, previous.outcome());
      return previous;
    }

    sleep(latency);
    return resultsByKey.computeIfAbsent(idempotencyKey, key -> decide(amount));
  }

  private PaymentProviderResult decide(BigDecimal amount) {
    String reference = "STUB-" + UUID.randomUUID().toString();
    if (amount.compareTo(declinedAbove) > 0) {
      return PaymentProviderResult.declined(reference, "Amount exceeds stub limit");
    }
    return PaymentProviderResult.succeeded(reference);
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while calling stub provider");
    }
  }
}
