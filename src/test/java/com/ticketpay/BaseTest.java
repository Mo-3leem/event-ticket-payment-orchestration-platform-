package com.ticketpay;

import com.ticketpay.identity.AdminBootstrapGate;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Integration tests run against real PostgreSQL. The datasource must be supplied explicitly via
 * TEST_DATABASE_URL, TEST_DATABASE_USER, and TEST_DATABASE_PASSWORD; the fail-closed guard rejects
 * missing configuration and the shared ticketpay database before any connection is opened.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(BaseTest.OpenGateConfiguration.class)
public abstract class BaseTest {

  @DynamicPropertySource
  static void testDatabaseProperties(DynamicPropertyRegistry registry) {
    TestDatabase.register(registry);
  }

  @TestConfiguration
  static class OpenGateConfiguration {

    @Bean
    ApplicationRunner openAdminBootstrapGate(AdminBootstrapGate gate) {
      return args -> gate.open();
    }
  }
}
