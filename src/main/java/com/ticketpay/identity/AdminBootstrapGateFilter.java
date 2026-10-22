package com.ticketpay.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketpay.shared.config.CorrelationIdFilter;
import com.ticketpay.shared.error.ApiError;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.MDC;
import org.springframework.http.MediaType;

public class AdminBootstrapGateFilter implements Filter {

  private static final int STATUS = HttpServletResponse.SC_SERVICE_UNAVAILABLE;
  private static final String CODE = "SERVICE_UNAVAILABLE";
  private static final String MESSAGE = "Application bootstrap is in progress";

  private final AdminBootstrapGate gate;
  private final ObjectMapper objectMapper;

  public AdminBootstrapGateFilter(AdminBootstrapGate gate, ObjectMapper objectMapper) {
    this.gate = gate;
    this.objectMapper = objectMapper;
  }

  @Override
  public void doFilter(
      ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
      throws IOException, ServletException {
    if (!(servletRequest instanceof HttpServletRequest request)
        || !(servletResponse instanceof HttpServletResponse response)) {
      filterChain.doFilter(servletRequest, servletResponse);
      return;
    }

    if (gate.isOpen()) {
      filterChain.doFilter(request, response);
      return;
    }

    String correlationId = CorrelationIdFilter.resolveCorrelationId(request, response);
    MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
    try {
      response.setStatus(STATUS);
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response.setHeader("Cache-Control", "no-store");
      response.setHeader(CorrelationIdFilter.HEADER, correlationId);
      response
          .getWriter()
          .write(
              objectMapper.writeValueAsString(
                  ApiError.of(STATUS, CODE, MESSAGE, correlationId)));
    } finally {
      MDC.remove(CorrelationIdFilter.MDC_KEY);
    }
  }
}
