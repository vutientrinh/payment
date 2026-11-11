package com.example.payment;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PaymentApplicationTests {
  @Autowired private Flyway flyway;

  @Test
  void contextLoads() {}

  @Test
  void flywayAppliedAllMigrationsOnFreshDatabase() {
    MigrationInfo[] applied = flyway.info().applied();
    assertThat(applied)
        .extracting(info -> info.getVersion().getVersion())
        .containsExactly("1", "2", "3");
    assertThat(applied).allMatch(info -> info.getState().isApplied());
    assertThat(flyway.info().pending()).isEmpty();
  }
}
