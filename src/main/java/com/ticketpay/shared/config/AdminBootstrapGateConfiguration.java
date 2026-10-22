package com.ticketpay.shared.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketpay.identity.AdminBootstrapGate;
import com.ticketpay.identity.AdminBootstrapGateFilter;
import jakarta.servlet.DispatcherType;
import java.util.EnumSet;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class AdminBootstrapGateConfiguration {

  @Bean
  public FilterRegistrationBean<AdminBootstrapGateFilter> adminBootstrapGateFilter(
      AdminBootstrapGate gate, ObjectMapper objectMapper) {
    FilterRegistrationBean<AdminBootstrapGateFilter> registration =
        new FilterRegistrationBean<>(new AdminBootstrapGateFilter(gate, objectMapper));
    registration.setName("adminBootstrapGateFilter");
    registration.addUrlPatterns("/*");
    registration.setDispatcherTypes(EnumSet.allOf(DispatcherType.class));
    registration.setAsyncSupported(true);
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
  }
}
