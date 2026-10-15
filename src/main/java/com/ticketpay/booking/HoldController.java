package com.ticketpay.booking;

import com.ticketpay.booking.dto.HoldRequest;
import com.ticketpay.booking.dto.HoldResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

  @GetMapping("/mine")
  public Page<HoldResponse> mine(
      Authentication authentication, @PageableDefault(size = 20) Pageable pageable) {
    UUID userId = UUID.fromString(authentication.getName());
    return holds.myHolds(userId, pageable);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void release(@PathVariable UUID id, Authentication authentication) {
    UUID userId = UUID.fromString(authentication.getName());
    holds.releaseHold(id, userId);
  }
}
