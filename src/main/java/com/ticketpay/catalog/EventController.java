package com.ticketpay.catalog;

import com.ticketpay.catalog.dto.EventDto;
import com.ticketpay.catalog.dto.EventSeatDto;
import com.ticketpay.shared.error.NotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/events")
public class EventController {

  private final EventRepository events;
  private final EventSeatRepository eventSeats;

  public EventController(EventRepository events, EventSeatRepository eventSeats) {
    this.events = events;
    this.eventSeats = eventSeats;
  }

  @GetMapping
  public Page<EventDto> list(@PageableDefault(size = 20) Pageable pageable) {
    return events.findAll(pageable).map(this::toDto);
  }

  @GetMapping("/{id}")
  public EventDto get(@PathVariable UUID id) {
    Event event = events.findById(id).orElseThrow(() -> new NotFoundException("Event not found"));
    return toDto(event);
  }

  @GetMapping("/{id}/seats")
  public List<EventSeatDto> seats(@PathVariable UUID id) {
    if (!events.existsById(id)) {
      throw new NotFoundException("Event not found");
    }
    return eventSeats.findByEventId(id).stream()
        .map(
            es ->
                new EventSeatDto(
                    es.getId(),
                    es.getSeat().getSeatCode(),
                    es.getStatus().name(),
                    es.getHeldByUserId(),
                    es.getHoldExpiresAt()))
        .toList();
  }

  private EventDto toDto(Event e) {
    return new EventDto(
        e.getId(),
        e.getTitle(),
        e.getVenue().getId(),
        e.getVenue().getName(),
        e.getStartsAt(),
        e.getPriceMinor(),
        e.getCurrency());
  }
}
