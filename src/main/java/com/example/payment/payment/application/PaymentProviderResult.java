package com.example.payment.payment.application;

public record PaymentProviderResult(
    Outcome outcome, String providerReference, String failureReason) {
  public enum Outcome {
    SUCCEEDED,
    DECLINED
  }

  public static PaymentProviderResult succeeded(String providerReference) {
    return new PaymentProviderResult(Outcome.SUCCEEDED, providerReference, null);
  }

  public static PaymentProviderResult declined(String providerReference, String failureReason) {
    return new PaymentProviderResult(Outcome.DECLINED, providerReference, failureReason);
  }
}
