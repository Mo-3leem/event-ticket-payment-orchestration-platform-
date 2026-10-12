package com.ticketpay.booking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class HoldExpiryJob {

  private static final Logger log = LoggerFactory.getLogger(HoldExpiryJob.class);

  private final HoldService holds;

  public HoldExpiryJob(HoldService holds) {
    this.holds = holds;
  }

  @Scheduled(fixedDelayString = "${app.hold.expiry-check-ms:60000}")
  public void expire() {
    int released = holds.releaseExpired();
    if (released > 0) {
      log.info("Released {} expired holds", released);
    }
  }
}
