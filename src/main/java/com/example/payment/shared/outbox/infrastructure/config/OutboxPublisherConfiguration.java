package com.example.payment.shared.outbox.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OutboxPublisherProperties.class)
public class OutboxPublisherConfiguration {}
