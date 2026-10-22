package com.ticketpay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketpay.identity.AdminBootstrapGate;
import com.ticketpay.identity.AdminBootstrapGateFilter;
import com.ticketpay.shared.config.AdminBootstrapGateConfiguration;
import com.ticketpay.shared.config.CorrelationIdFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminBootstrapGateTest {

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private final AdminBootstrapGate gate = new AdminBootstrapGate();
  private final AdminBootstrapGateFilter filter =
      new AdminBootstrapGateFilter(gate, objectMapper);
  private final AtomicBoolean reached = new AtomicBoolean(false);
  private final FilterChain chain = (request, response) -> reached.set(true);

  @Test
  void gate_starts_closed_and_rejects_every_path_without_reaching_the_chain() throws Exception {
    assertThat(gate.isOpen()).isFalse();

    for (String path :
        List.of("/api/v1/auth/register", "/api/v1/events", "/actuator/health", "/")) {
      reached.set(false);
      MockHttpServletResponse response = closedResponse(new MockHttpServletRequest("GET", path));

      assertRejected(response);
      assertThat(reached).isFalse();
    }
  }

  @Test
  void every_dispatcher_type_is_rejected_while_closed() throws Exception {
    for (DispatcherType dispatcherType : DispatcherType.values()) {
      reached.set(false);
      MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/events");
      request.setDispatcherType(dispatcherType);
      MockHttpServletResponse response = new MockHttpServletResponse();

      filter.doFilter(request, response, chain);

      assertThat(response.getStatus()).as(dispatcherType.name()).isEqualTo(503);
      assertThat(reached).as(dispatcherType.name()).isFalse();
    }
  }

  @Test
  void closed_response_uses_the_standard_error_shape_and_a_valid_correlation_id()
      throws Exception {
    MockHttpServletResponse response =
        closedResponse(new MockHttpServletRequest("GET", "/api/v1/events"));

    String correlationId = response.getHeader(CorrelationIdFilter.HEADER);
    UUID.fromString(correlationId);
    JsonNode body = objectMapper.readTree(response.getContentAsString());

    assertThat(body.get("status").asInt()).isEqualTo(503);
    assertThat(body.get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
    assertThat(body.get("message").asText()).isEqualTo("Application bootstrap is in progress");
    assertThat(body.get("correlationId").asText()).isEqualTo(correlationId);
    assertThat(body.get("timestamp").asText()).isNotBlank();
    assertThat(body.get("fieldErrors").isArray()).isTrue();
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
  }

  @Test
  void valid_client_correlation_id_is_reused_without_conflict() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/events");
    request.addHeader(CorrelationIdFilter.HEADER, "client-correlation-123");
    MockHttpServletResponse response = closedResponse(request);

    assertThat(response.getHeader(CorrelationIdFilter.HEADER))
        .isEqualTo("client-correlation-123");
    assertThat(
            objectMapper
                .readTree(response.getContentAsString())
                .get("correlationId")
                .asText())
        .isEqualTo("client-correlation-123");
  }

  @Test
  void existing_response_correlation_id_is_reused_when_request_has_none() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    response.setHeader(CorrelationIdFilter.HEADER, "upstream-correlation-456");

    filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/events"), response, chain);

    assertThat(response.getHeader(CorrelationIdFilter.HEADER))
        .isEqualTo("upstream-correlation-456");
  }

  @Test
  void missing_or_invalid_correlation_id_is_replaced() throws Exception {
    String tooLong = "x".repeat(101);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/events");
    request.addHeader(CorrelationIdFilter.HEADER, tooLong);
    MockHttpServletResponse response = closedResponse(request);

    String generated = response.getHeader(CorrelationIdFilter.HEADER);
    assertThat(generated).isNotEqualTo(tooLong);
    UUID.fromString(generated);
  }

  @Test
  void open_gate_allows_the_request_to_reach_the_chain_for_every_dispatcher_type()
      throws Exception {
    gate.open();

    for (DispatcherType dispatcherType : DispatcherType.values()) {
      reached.set(false);
      MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/events");
      request.setDispatcherType(dispatcherType);
      MockHttpServletResponse response = new MockHttpServletResponse();

      filter.doFilter(request, response, chain);

      assertThat(reached).as(dispatcherType.name()).isTrue();
      assertThat(response.getStatus()).isEqualTo(200);
    }
  }

  @Test
  void registration_order_precedes_spring_security_and_covers_all_dispatches() {
    FilterRegistrationBean<AdminBootstrapGateFilter> registration =
        new AdminBootstrapGateConfiguration().adminBootstrapGateFilter(gate, objectMapper);

    assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    assertThat(registration.getOrder()).isLessThan(SecurityProperties.DEFAULT_FILTER_ORDER);
    assertThat(registration.getUrlPatterns()).containsExactly("/*");
    assertThat(registration.determineDispatcherTypes())
        .containsExactlyInAnyOrder(DispatcherType.values());
    assertThat(registration.getFilter()).isInstanceOf(AdminBootstrapGateFilter.class);
  }

  private MockHttpServletResponse closedResponse(MockHttpServletRequest request)
      throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, chain);
    return response;
  }

  private void assertRejected(MockHttpServletResponse response) throws Exception {
    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
    JsonNode body = objectMapper.readTree(response.getContentAsString());
    assertThat(body.get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
    assertThat(body.get("correlationId").asText())
        .isEqualTo(response.getHeader(CorrelationIdFilter.HEADER));
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
  }
}
