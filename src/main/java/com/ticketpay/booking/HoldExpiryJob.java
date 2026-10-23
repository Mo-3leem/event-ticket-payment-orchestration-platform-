package com.ticketpay.booking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class HoldExpiryJob {

  private static final Logger log = LoggerFactory.getLogger(HoldExpiryJob.class);

  private final HoldService holds;
  private final BookingService bookings;

  public HoldExpiryJob(HoldService holds, BookingService bookings) {
    this.holds = holds;
    this.bookings = bookings;
  }

  @Scheduled(fixedDelayString = "${app.hold.expiry-check-ms:60000}")
  public void expire() {
    try {
      int releasedHolds = holds.releaseExpired();
      if (releasedHolds > 0) {
        log.info("Released {} expired holds", releasedHolds);
      }
    } catch (RuntimeException failure) {
      log.error("Hold expiry cycle failed", failure);
    }
    try {
      int expiredBookings = bookings.expireBookings();
      if (expiredBookings > 0) {
        log.info("Expired {} unpaid bookings", expiredBookings);
      }
    } catch (RuntimeException failure) {
      log.error("Booking expiry cycle failed", failure);
    }
  }
}
