package com.ticketpay.booking;

import com.ticketpay.booking.dto.BookingDto;
import com.ticketpay.booking.dto.BookingResponse;
import com.ticketpay.booking.dto.CreateBookingRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

  private final BookingService bookings;

  public BookingController(BookingService bookings) {
    this.bookings = bookings;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public BookingResponse create(
      @Valid @RequestBody CreateBookingRequest request, Authentication authentication) {
    UUID userId = UUID.fromString(authentication.getName());
    return bookings.createBooking(userId, request.holdIds());
  }

  @GetMapping("/me")
  public Page<BookingDto> mine(
      Authentication authentication, @PageableDefault(size = 20) Pageable pageable) {
    UUID userId = UUID.fromString(authentication.getName());
    return bookings.myBookings(userId, pageable);
  }
}
