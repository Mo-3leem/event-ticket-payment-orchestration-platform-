package com.ticketpay;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketpay.catalog.Event;
import com.ticketpay.catalog.EventRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

class CatalogTest extends BaseTest {

  @Autowired MockMvc mvc;
  @Autowired EventRepository events;

  @Test
  void list_events_is_public() throws Exception {
    mvc.perform(get("/api/v1/events"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").exists());
  }

  @Test
  void get_event_seats_returns_inventory() throws Exception {
    Event event = events.findAll().stream().findFirst().orElseThrow();
    mvc.perform(get("/api/v1/events/" + event.getId() + "/seats"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$.length()").value(10));
  }

  @Test
  void unknown_event_returns_404() throws Exception {
    mvc.perform(get("/api/v1/events/" + UUID.randomUUID() + "/seats"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }
}
