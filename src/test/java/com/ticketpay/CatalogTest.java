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
  void public_inventory_never_exposes_holder_identity() throws Exception {
    Event event = events.findAll().stream().findFirst().orElseThrow();
    mvc.perform(get("/api/v1/events/" + event.getId() + "/seats"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].heldByUserId").doesNotExist())
        .andExpect(jsonPath("$[0].eventSeatId").exists())
        .andExpect(jsonPath("$[0].seatCode").exists());
  }

  @Test
  void get_event_by_id_returns_event() throws Exception {
    Event event = events.findAll().stream().findFirst().orElseThrow();
    mvc.perform(get("/api/v1/events/" + event.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(event.getId().toString()))
        .andExpect(jsonPath("$.title").value(event.getTitle()))
        .andExpect(jsonPath("$.priceMinor").value(event.getPriceMinor()));
  }

  @Test
  void unknown_event_returns_404() throws Exception {
    mvc.perform(get("/api/v1/events/" + UUID.randomUUID() + "/seats"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }
}
