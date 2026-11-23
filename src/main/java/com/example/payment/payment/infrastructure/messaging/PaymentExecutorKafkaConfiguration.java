package com.example.payment.payment.infrastructure.messaging;

import com.example.payment.shared.application.ResourceNotFoundException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

@Configuration
@ConditionalOnProperty(name = "payment.executor.enabled", havingValue = "true")
public class PaymentExecutorKafkaConfiguration {
  @Bean
  DefaultErrorHandler kafkaErrorHandler() {
    DefaultErrorHandler errorHandler = new DefaultErrorHandler(new FixedBackOff(1_000L, 5L));
    errorHandler.addNotRetryableExceptions(JacksonException.class, ResourceNotFoundException.class);
    return errorHandler;
  }
}
