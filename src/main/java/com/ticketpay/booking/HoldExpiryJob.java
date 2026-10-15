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
    int releasedHolds = holds.releaseExpired();
    int expiredBookings = bookings.expireBookings();
    if (releasedHolds > 0 || expiredBookings > 0) {
      log.info("Released {} expired holds, expired {} bookings", releasedHolds, expiredBookings);
    }
  }
}
