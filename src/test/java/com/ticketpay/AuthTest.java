package com.ticketpay;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketpay.identity.dto.LoginRequest;
import com.ticketpay.identity.dto.RegisterRequest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class AuthTest extends BaseTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;

  private String uniqueEmail() {
    return "user-" + UUID.randomUUID() + "@example.com";
  }

  @Test
  void register_then_login_then_me() throws Exception {
    String email = uniqueEmail();

    mvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RegisterRequest(email, "password123"))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.token").exists())
        .andExpect(jsonPath("$.email").value(email))
        .andExpect(jsonPath("$.role").value("USER"));

    mvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(email, "password123"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").exists());
  }

  @Test
  void public_registration_ignores_a_requested_admin_role() throws Exception {
    String email = uniqueEmail();
    String body =
        objectMapper.writeValueAsString(
            Map.of("email", email, "password", "password123", "role", "ADMIN"));

    mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.role").value("USER"));
  }

  @Test
  void duplicate_registration_returns_409() throws Exception {
    String email = uniqueEmail();
    var body = objectMapper.writeValueAsString(new RegisterRequest(email, "password123"));

    mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated());

    mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONFLICT"));
  }

  @Test
  void registration_rejects_email_longer_than_storage_limit() throws Exception {
    String email = "a".repeat(246) + "@example.com"; // 258 chars, over VARCHAR(255)
    mvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RegisterRequest(email, "password123"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  void registration_rejects_password_over_bcrypt_limit() throws Exception {
    mvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new RegisterRequest(uniqueEmail(), "p".repeat(73)))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  void registration_rejects_multibyte_password_over_utf8_byte_limit() throws Exception {
    mvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new RegisterRequest(uniqueEmail(), "é".repeat(40)))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
  }

  @Test
  void login_with_wrong_password_returns_401() throws Exception {
    String email = uniqueEmail();
    mvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RegisterRequest(email, "password123"))))
        .andExpect(status().isCreated());

    mvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(email, "wrong-password"))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void me_without_token_returns_401_json() throws Exception {
    mvc.perform(get("/api/v1/auth/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
        .andExpect(jsonPath("$.correlationId").exists());
  }

  @Test
  void me_with_token_returns_user_id() throws Exception {
    String email = uniqueEmail();
    String registerBody =
        objectMapper.writeValueAsString(new RegisterRequest(email, "password123"));

    String response =
        mvc.perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(registerBody))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String token = objectMapper.readTree(response).get("token").asText();

    mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").exists());
  }
}
