package com.ticketpay;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests run against real PostgreSQL started via {@code docker compose up -d}
 * (see docker-compose.yml). The test profile (src/test/resources/application-test.yml)
 * configures test-specific timing and logging settings; the datasource is inherited from
 * application.yml and can be pointed at a dedicated test database with DATABASE_URL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class BaseTest {}
