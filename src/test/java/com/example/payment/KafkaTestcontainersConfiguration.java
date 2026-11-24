package com.example.payment;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class KafkaTestcontainersConfiguration {
  @Bean
  @ServiceConnection
  KafkaContainer kafkaContainer() {
    return new KafkaContainer(DockerImageName.parse("apache/kafka:4.4.0"));
  }
  ;

  @Bean
  NewTopic paymentRequestTopic() {
    return TopicBuilder.name("payment.requested.v1").partitions(3).replicas(1).build();
  }

  @Bean
  NewTopic paymentStatusChangedTopic() {
    return TopicBuilder.name("payment.status-changed.v1").partitions(3).replicas(1).build();
  }
  ;
}
