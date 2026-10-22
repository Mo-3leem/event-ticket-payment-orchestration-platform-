package com.ticketpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.support.SpringFactoriesLoader;

class TestDatasourceGuardInitializerTest {

  private static final String DEDICATED_URL =
      "jdbc:postgresql://localhost:5433/ticketpay_test";
  private static final String SHARED_URL = "jdbc:postgresql://localhost:5433/ticketpay";

  @Test
  void pins_every_alias_and_installs_the_datasource_guard_without_starting_a_pool() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      new TestDatasourceGuardInitializer(validEnvironment()).initialize(context);
      context.refresh();

      assertThat(context.getEnvironment().getPropertySources())
          .extracting(source -> source.getName())
          .contains(TestDatasourceGuardInitializer.PROPERTY_SOURCE_NAME);
      assertThat(context.getEnvironment().getProperty(TestDatabase.DATASOURCE_URL))
          .isEqualTo(DEDICATED_URL);
      assertThat(context.getEnvironment().getProperty(TestDatabase.HIKARI_JDBC_URL))
          .isEqualTo(DEDICATED_URL);
      assertThat(context.getEnvironment().getProperty(TestDatabase.FLYWAY_URL))
          .isEqualTo(DEDICATED_URL);
    }
  }

  @Test
  void non_base_test_context_with_a_shared_datasource_fails_before_connecting() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      new TestDatasourceGuardInitializer(validEnvironment()).initialize(context);
      context.registerBean(
          "dataSource", HikariDataSource.class, () -> hikariDataSource(SHARED_URL));

      assertThatThrownBy(context::refresh)
          .isInstanceOf(BeanCreationException.class)
          .hasRootCauseInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void non_base_test_context_with_an_unverifiable_datasource_fails_before_connecting() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      new TestDatasourceGuardInitializer(validEnvironment()).initialize(context);
      context.registerBean("dataSource", DataSource.class, () -> mock(DataSource.class));

      assertThatThrownBy(context::refresh)
          .isInstanceOf(BeanCreationException.class)
          .hasRootCauseInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void database_free_context_without_test_variables_remains_startable() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      new TestDatasourceGuardInitializer(Map.of()).initialize(context);
      context.refresh();

      assertThat(context.getEnvironment().getPropertySources())
          .extracting(source -> source.getName())
          .doesNotContain(TestDatasourceGuardInitializer.PROPERTY_SOURCE_NAME);
    }
  }

  @Test
  void initializer_is_registered_for_spring_boot_test_contexts() {
    assertThat(
            SpringFactoriesLoader.loadFactoryNames(
                ApplicationContextInitializer.class, getClass().getClassLoader()))
        .contains(TestDatasourceGuardInitializer.class.getName());
  }

  private static Map<String, String> validEnvironment() {
    return Map.of(
        TestDatabase.URL_ENV, DEDICATED_URL,
        TestDatabase.USER_ENV, "test_user",
        TestDatabase.PASSWORD_ENV, "test_password");
  }

  private static HikariDataSource hikariDataSource(String url) {
    HikariDataSource dataSource = new HikariDataSource();
    dataSource.setJdbcUrl(url);
    dataSource.setUsername("test_user");
    dataSource.setPassword("test_password");
    return dataSource;
  }
}
