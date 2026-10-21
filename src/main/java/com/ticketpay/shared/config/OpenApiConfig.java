package com.ticketpay.shared.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

  @Bean
  public OpenAPI openAPI() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Event Ticket & Payment Orchestration Platform")
                .version("1.0.0")
                .description(
                    "Seat inventory with transactional holds, bookings, mock-provider payment"
                        + " orchestration with idempotency, and Admin-driven reconciliation and"
                        + " refunds. Public catalog endpoints are unauthenticated; everything"
                        + " else requires a JWT bearer token, and /api/v1/admin/** requires"
                        + " ROLE_ADMIN."))
        .components(
            new Components()
                .addSecuritySchemes(
                    "bearerAuth",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")));
  }
}
