package com.ticketpay.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class AdminBootstrapRunner implements ApplicationRunner {

  private final AdminBootstrapService bootstrap;
  private final AdminBootstrapGate gate;
  private final String email;
  private final String password;

  public AdminBootstrapRunner(
      AdminBootstrapService bootstrap,
      AdminBootstrapGate gate,
      @Value("${app.bootstrap.admin.email:}") String email,
      @Value("${app.bootstrap.admin.password:}") String password) {
    this.bootstrap = bootstrap;
    this.gate = gate;
    this.email = email;
    this.password = password;
  }

  @Override
  public void run(ApplicationArguments args) {
    bootstrap.bootstrap(email, password);
    gate.open();
  }
}
