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
