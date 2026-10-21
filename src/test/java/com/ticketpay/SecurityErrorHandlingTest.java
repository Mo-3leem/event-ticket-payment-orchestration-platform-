package com.ticketpay;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketpay.identity.JwtService;
import com.ticketpay.identity.dto.RegisterRequest;
import com.ticketpay.shared.config.CorrelationIdFilter;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class SecurityErrorHandlingTest extends BaseTest {

  private static final String WRONG_SIGNING_KEY =
      "wrong-test-signing-key-0123456789-abcdefghijklmnopqrstuvwxyz";
  private static final Instant TOKEN_ISSUED_AT = Instant.parse("2020-01-01T00:00:00Z");
  private static final Instant EXPIRED_AT = Instant.parse("2020-01-01T00:01:00Z");
  private static final Instant FUTURE_AT = Instant.parse("2999-01-01T00:00:00Z");

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired JwtService jwtService;

  @Value("${app.jwt.secret}")
  String jwtSecret;

  private String registerAndGetToken() throws Exception {
    String email = "security-" + UUID.randomUUID() + "@example.com";
    String response =
        mvc.perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            new RegisterRequest(email, "password123"))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(response).get("token").asText();
  }

  private SecretKey signingKey(String secret) {
    return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
  }

  private String tokenSignedWith(
      SecretKey key, UUID userId, Instant issuedAt, Instant expiresAt) {
    return Jwts.builder()
        .subject(userId.toString())
        .issuedAt(Date.from(issuedAt))
        .expiration(Date.from(expiresAt))
        .signWith(key)
        .compact();
  }

  private void assertUnauthorizedJson(String token, String correlationId) throws Exception {
    mvc.perform(
            get("/api/v1/auth/me")
                .header("Authorization", "Bearer " + token)
                .header(CorrelationIdFilter.HEADER, correlationId))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(header().string(CorrelationIdFilter.HEADER, correlationId))
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
        .andExpect(jsonPath("$.correlationId").value(correlationId))
        .andExpect(jsonPath("$.timestamp").exists())
        .andExpect(jsonPath("$.fieldErrors").isArray());
  }

  @Test
  void missing_token_returns_json_401() throws Exception {
    mvc.perform(get("/api/v1/auth/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
        .andExpect(jsonPath("$.message").isNotEmpty())
        .andExpect(jsonPath("$.correlationId").isNotEmpty())
        .andExpect(jsonPath("$.timestamp").exists())
        .andExpect(jsonPath("$.fieldErrors").isArray());
  }

  @Test
  void invalid_token_returns_json_401() throws Exception {
    mvc.perform(
            get("/api/v1/auth/me")
                .header("Authorization", "Bearer not-a-valid-jwt"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
        .andExpect(jsonPath("$.correlationId").isNotEmpty());
  }

  @Test
  void expired_token_returns_json_401() throws Exception {
    String validToken = registerAndGetToken();
    UUID userId = UUID.fromString(jwtService.extractUserId(validToken));
    String token =
        tokenSignedWith(signingKey(jwtSecret), userId, TOKEN_ISSUED_AT, EXPIRED_AT);

    assertUnauthorizedJson(token, "expired-token-correlation");
  }

  @Test
  void wrong_signature_token_returns_json_401() throws Exception {
    String validToken = registerAndGetToken();
    UUID userId = UUID.fromString(jwtService.extractUserId(validToken));
    String token =
        tokenSignedWith(signingKey(WRONG_SIGNING_KEY), userId, TOKEN_ISSUED_AT, FUTURE_AT);

    assertUnauthorizedJson(token, "wrong-signature-correlation");
  }

  @Test
  void authenticated_non_admin_returns_json_403() throws Exception {
    String token = registerAndGetToken();
    String correlationId = "forbidden-correlation";

    mvc.perform(
            get("/api/v1/admin/payments")
                .header("Authorization", "Bearer " + token)
                .header(CorrelationIdFilter.HEADER, correlationId))
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(header().string(CorrelationIdFilter.HEADER, correlationId))
        .andExpect(jsonPath("$.status").value(403))
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.message").isNotEmpty())
        .andExpect(jsonPath("$.correlationId").value(correlationId))
        .andExpect(jsonPath("$.timestamp").exists())
        .andExpect(jsonPath("$.fieldErrors").isArray());
  }

  @Test
  void correlation_id_is_echoed_in_header_and_body() throws Exception {
    String correlationId = "test-correlation-" + UUID.randomUUID();

    mvc.perform(
            get("/api/v1/auth/me")
                .header(CorrelationIdFilter.HEADER, correlationId))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(CorrelationIdFilter.HEADER, correlationId))
        .andExpect(jsonPath("$.correlationId").value(correlationId));
  }

  @Test
  void public_events_endpoint_remains_public() throws Exception {
    mvc.perform(get("/api/v1/events"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
  }
}
