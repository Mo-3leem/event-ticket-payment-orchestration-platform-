package com.ticketpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketpay.catalog.EventController;
import com.ticketpay.catalog.EventRepository;
import com.ticketpay.catalog.EventSeatRepository;
import com.ticketpay.identity.AdminBootstrapGate;
import com.ticketpay.identity.AdminBootstrapRunner;
import com.ticketpay.identity.AdminBootstrapService;
import com.ticketpay.identity.AuthController;
import com.ticketpay.identity.AuthService;
import com.ticketpay.identity.JwtAuthFilter;
import com.ticketpay.identity.JwtService;
import com.ticketpay.identity.UserRepository;
import com.ticketpay.identity.dto.AuthResponse;
import com.ticketpay.identity.dto.RegisterRequest;
import com.ticketpay.shared.config.AdminBootstrapGateConfiguration;
import com.ticketpay.shared.config.CorrelationIdFilter;
import com.ticketpay.shared.config.SecurityConfig;
import com.ticketpay.shared.error.SecurityErrorHandler;
import jakarta.servlet.ServletContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = AdminBootstrapGateWebTest.TestApplication.class,
    properties = {
      "management.endpoints.web.exposure.include=health",
      "spring.main.banner-mode=off"
    })
class AdminBootstrapGateWebTest {

  private static final String EMAIL = "admin@example.com";
  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final String REGISTER_BODY =
      "{\"email\":\"new@example.com\",\"password\":\"password123\"}";

  @Autowired AdminBootstrapGate gate;
  @Autowired TestRestTemplate rest;
  @Autowired ServletContext servletContext;

  @MockitoBean AuthService authService;
  @MockitoBean EventRepository eventRepository;
  @MockitoBean EventSeatRepository eventSeatRepository;
  @MockitoBean JwtService jwtService;
  @MockitoBean UserRepository userRepository;

  @Test
  void gate_filter_is_installed_in_the_real_servlet_context() {
    assertThat(servletContext.getFilterRegistration("adminBootstrapGateFilter"))
        .isInstanceOf(jakarta.servlet.FilterRegistration.class);
  }

  @Test
  void closed_gate_blocks_auth_catalog_and_actuator_before_security_or_handlers() {
    assertThat(gate.isOpen()).isFalse();

    ResponseEntity<String> register =
        rest.postForEntity(
            "/api/v1/auth/register", json(REGISTER_BODY, "bogus-token"), String.class);
    ResponseEntity<String> events =
        rest.exchange(
            "/api/v1/events",
            HttpMethod.GET,
            new HttpEntity<>(headers("bogus-token")),
            String.class);
    ResponseEntity<String> health = rest.getForEntity("/actuator/health", String.class);

    assertRejected(register);
    assertRejected(events);
    assertRejected(health);
    verifyNoInteractions(
        authService, eventRepository, eventSeatRepository, jwtService, userRepository);
  }

  @Test
  void bootstrap_failure_leaves_the_gate_closed() {
    AdminBootstrapService bootstrap = mock(AdminBootstrapService.class);
    doThrow(new IllegalStateException("bootstrap failed"))
        .when(bootstrap)
        .bootstrap(EMAIL, PASSWORD);

    assertThatThrownBy(() -> new AdminBootstrapRunner(bootstrap, gate, EMAIL, PASSWORD).run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("bootstrap failed");

    assertThat(gate.isOpen()).isFalse();
    assertRejected(rest.getForEntity("/api/v1/events", String.class));
  }

  @Test
  @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
  void opening_the_gate_allows_requests_to_reach_the_handlers() {
    gate.open();
    when(eventRepository.findAllByOrderByStartsAtAscIdAsc(any(Pageable.class)))
        .thenReturn(Page.empty());
    when(authService.register(any(RegisterRequest.class)))
        .thenReturn(new AuthResponse("token", "new@example.com", "USER"));

    ResponseEntity<String> events = rest.getForEntity("/api/v1/events", String.class);
    ResponseEntity<String> register =
        rest.postForEntity("/api/v1/auth/register", json(REGISTER_BODY), String.class);

    assertThat(events.getStatusCode().value()).isEqualTo(200);
    assertThat(register.getStatusCode().value()).isEqualTo(201);
    assertThat(register.getBody()).contains("new@example.com");
  }

  private void assertRejected(ResponseEntity<String> response) {
    assertThat(response.getStatusCode().value()).isEqualTo(503);
    assertThat(response.getHeaders().getFirst(CorrelationIdFilter.HEADER)).isNotBlank();
    assertThat(response.getBody()).contains("\"code\":\"SERVICE_UNAVAILABLE\"");
    assertThat(response.getBody())
        .contains(
            "\"correlationId\":\""
                + response.getHeaders().getFirst(CorrelationIdFilter.HEADER)
                + "\"");
  }

  private HttpEntity<String> json(String body) {
    return json(body, null);
  }

  private HttpEntity<String> json(String body, String bearerToken) {
    HttpHeaders headers = headers(bearerToken);
    headers.setContentType(MediaType.APPLICATION_JSON);
    return new HttpEntity<>(body, headers);
  }

  private HttpHeaders headers(String bearerToken) {
    HttpHeaders headers = new HttpHeaders();
    if (bearerToken != null) {
      headers.setBearerAuth(bearerToken);
    }
    return headers;
  }

  @Configuration
  @EnableAutoConfiguration(
      exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        JpaRepositoriesAutoConfiguration.class,
        FlywayAutoConfiguration.class
      })
  @Import({
    AdminBootstrapGate.class,
    AdminBootstrapGateConfiguration.class,
    SecurityConfig.class,
    SecurityErrorHandler.class,
    CorrelationIdFilter.class,
    JwtAuthFilter.class,
    AuthController.class,
    EventController.class
  })
  static class TestApplication {}
}
