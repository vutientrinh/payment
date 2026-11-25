package com.example.payment.payment.infrastructure.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.payment.payment.application.PaymentProviderResult;
import com.example.payment.payment.application.ProviderOutcomeUnknownException;
import com.example.payment.payment.domain.PaymentMethod;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class StubPaymentProviderTest {
  private final StubPaymentProvider provider =
      new StubPaymentProvider(Duration.ZERO, new BigDecimal("1000"), new BigDecimal("777"));

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

  @Test
  void timeoutAmountChargeButLosesTheResponse() {
    assertThatThrownBy(
            () -> provider.charge(3L, new BigDecimal("777"), PaymentMethod.CARD, "payment-3"))
        .isInstanceOf(ProviderOutcomeUnknownException.class);
    assertThat(provider.findByIdempotencyKey("payment-3"))
        .hasValueSatisfying(
            result ->
                assertThat(result.outcome()).isEqualTo(PaymentProviderResult.Outcome.SUCCEEDED));
  }

  @Test
  void unknownKeyIsNotFound() {
    assertThat(provider.findByIdempotencyKey("payment-404")).isEmpty();
  }
}
