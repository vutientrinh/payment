package com.example.payment.payment.infrastructure.messaging;

import org.springframework.kafka.annotation.KafkaListener;

@KafkaListener(topics = "payment.requested.v1", groupId = "${payment.executor.consumer-group}")
public class PaymentRequestedConsumer {
}

