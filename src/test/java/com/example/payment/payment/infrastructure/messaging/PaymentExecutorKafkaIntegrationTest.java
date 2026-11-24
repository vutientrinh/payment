package com.example.payment.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.example.payment.KafkaTestcontainersConfiguration;
import com.example.payment.TestcontainersConfiguration;
import com.example.payment.payment.application.PaymentProvider;
import com.example.payment.payment.application.PaymentService;
import com.example.payment.payment.domain.PaymentMethod;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(
    properties = {
      "payment.outbox.publisher.enabled=true",
      "payment.executor.enabled=true",
      "payment.provider.stub.latency=0s"
    })
@Import({TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class})
class PaymentExecutorKafkaIntegrationTest {
  private static final String REQUESTED_TOPIC = "payment.requested.v1";
  private static final String STATUS_CHANGED_TOPIC = "payment.status-changed.v1";
  private static final String CONSUMER_GROUP = "payment-executor-v1";
  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  @Autowired private PaymentService paymentService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private KafkaTemplate<String, String> kafkaTemplate;
  @Autowired private KafkaAdmin kafkaAdmin;

  @MockitoSpyBean private PaymentProvider paymentProvider;

  @Test
  void paymentIsChargedOnceEventWhenTheEventIsDeliveredTwice() throws Exception {
    Long paymentId = createPayment(new BigDecimal("120000.00"));

    await().atMost(TIMEOUT).until(() -> "COMPLETED".equals(paymentStatus(paymentId)));
    assertThat(orderStatusOfPayment(paymentId)).isEqualTo("PAID");
    long versionBefore = paymentVersion(paymentId);
    String payload =
        jdbcTemplate.queryForObject(
            "select payload from outbox_events where event_type = ? and aggregate_id = ?",
            String.class,
            REQUESTED_TOPIC,
            paymentId);
    RecordMetadata replay =
        kafkaTemplate
            .send(REQUESTED_TOPIC, paymentId.toString(), payload)
            .get()
            .getRecordMetadata();
    TopicPartition topicPartition = new TopicPartition(REQUESTED_TOPIC, replay.partition());
    await().atMost(TIMEOUT).until(() -> commitedOffset(topicPartition) > replay.offset());
    verify(paymentProvider, times(1))
        .charge(eq(paymentId), any(), any(), eq("payment-" + paymentId));
    assertThat(paymentStatus(paymentId)).isEqualTo("COMPLETED");
    assertThat(paymentVersion(paymentId)).isEqualTo(versionBefore);
    assertThat(count("select count(*) from consumed_events where event_id = ?", eventIdOf(payload)))
        .isEqualTo(1);
    assertThat(
            count(
                "select count(*) from outbox_events where event_type = ? and aggregate_id = ?",
                STATUS_CHANGED_TOPIC,
                paymentId))
        .isEqualTo(2);
  }

  @Test
  void amountAboveStubLimitEndsFailed() {
    Long paymentId = createPayment(new BigDecimal("12000000.00"));
    await().atMost(TIMEOUT).until(() -> "FAILED".equals(paymentStatus(paymentId)));
    assertThat(orderStatusOfPayment(paymentId)).isEqualTo("PAYMENT_FAILED");
  }

  private long count(String query, Object... args) {
    return jdbcTemplate.queryForObject(query, Long.class, args);
  }

  private UUID eventIdOf(String payload) {
    return UUID.fromString(
        jdbcTemplate.queryForObject("select cast(? as jsonb)->> 'eventId'", String.class, payload));
  }

  private long paymentVersion(Long paymentId) {
    return jdbcTemplate.queryForObject(
        "select version from payments where id = ?", Long.class, paymentId);
  }

  private String orderStatusOfPayment(Long paymentId) {
    return jdbcTemplate.queryForObject(
        "select o.order_status from orders o join payments p on p.order_id = o.id where p.id = ?",
        String.class,
        paymentId);
  }

  private String paymentStatus(Long paymentId) {
    return jdbcTemplate.queryForObject(
        "select payment_status from payments where id = ?", String.class, paymentId);
  }

  private Long createPayment(BigDecimal amount) {
    String unique = UUID.randomUUID().toString();
    Long customerId =
        jdbcTemplate.queryForObject(
            """
                insert into users (first_name, last_name, email, phone_number, created_at, updated_at)
            values ('Test', 'User', ?, '0900000000', now(), now()) returning id
            """,
            Long.class,
            unique + "@example.com");

    Long productId =
        jdbcTemplate.queryForObject(
            """
                        insert into products (product_name, product_description, unit_price, created_at, updated_at)
                        values ('Coffee', 'Beans', ?, now(), now()) returning id
                        """,
            Long.class,
            amount);
    Long orderId =
        jdbcTemplate.queryForObject(
            """
                        insert into orders (order_code, order_status, order_amount, customer_id, created_at, updated_at)
                        values (?, 'PENDING', ?, ?, now(), now()) returning id
                        """,
            Long.class,
            "ORD-" + unique,
            amount,
            customerId);
    jdbcTemplate.update(
        """
                insert into order_items (quantity, price, order_id, product_id, created_at, updated_at)
                values (1, ?, ?, ?, now(), now())
                """,
        amount,
        orderId,
        productId);

    return paymentService.createPayment(orderId, PaymentMethod.EWALLET).id();
  }

  private long commitedOffset(TopicPartition topicPartition) throws Exception {
    try (Admin admin = Admin.create(kafkaAdmin.getConfigurationProperties())) {
      OffsetAndMetadata committed =
          admin
              .listConsumerGroupOffsets(CONSUMER_GROUP)
              .partitionsToOffsetAndMetadata()
              .get()
              .get(topicPartition);
      return committed == null ? -1 : committed.offset();
    }
  }
}
