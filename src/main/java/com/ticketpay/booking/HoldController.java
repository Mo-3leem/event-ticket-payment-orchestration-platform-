package com.ticketpay.booking;

import com.ticketpay.booking.dto.HoldRequest;
import com.ticketpay.booking.dto.HoldResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/holds")
public class HoldController {

  private final HoldService holds;

  public HoldController(HoldService holds) {
    this.holds = holds;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public HoldResponse acquire(@Valid @RequestBody HoldRequest request, Authentication authentication) {
    UUID userId = UUID.fromString(authentication.getName());
    return holds.acquire(request.eventSeatId(), userId);
  }
}
