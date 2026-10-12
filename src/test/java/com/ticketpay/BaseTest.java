package com.ticketpay;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests run against real PostgreSQL started via {@code docker compose up -d}
 * (see docker-compose.yml and src/test/resources/application.yml).
 *
 * <p>Testcontainers was evaluated first but is disabled for Day 1: the installed Docker
 * Desktop's Engine API proxy answers Testcontainers' version negotiation with HTTP 400,
 * so no container can be started from the test JVM. Compose-backed tests use the same
 * PostgreSQL engine, so row-locking and constraint behaviour under test is identical.
 * Revisit on Day 5 only if time remains.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class BaseTest {}
