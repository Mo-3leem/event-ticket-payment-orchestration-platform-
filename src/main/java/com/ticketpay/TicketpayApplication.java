package com.ticketpay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TicketpayApplication {
  public static void main(String[] args) {
    SpringApplication.run(TicketpayApplication.class, args);
  }
}
