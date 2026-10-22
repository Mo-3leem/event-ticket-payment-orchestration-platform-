package com.ticketpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;

class TestDatabaseTest {

  private static final String DEDICATED_URL =
      "jdbc:postgresql://localhost:5433/ticketpay_test";
  private static final String SHARED_URL = "jdbc:postgresql://localhost:5433/ticketpay";
  private static final String USERNAME = "test_user";
  private static final String PASSWORD = "test_password";

  @Test
  void missing_url_is_rejected() {
    assertThatThrownBy(() -> TestDatabase.databaseName(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(TestDatabase.URL_ENV);

    assertThatThrownBy(() -> TestDatabase.databaseName("  "))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(TestDatabase.URL_ENV);
  }

  @Test
  void shared_database_is_rejected_in_url_equivalent_forms() {
    List<String> sharedUrls =
        List.of(
            "jdbc:postgresql://localhost:5433/ticketpay",
            "jdbc:postgresql://localhost:5433/TICKETPAY?currentSchema=public",
            "jdbc:postgresql:ticketpay",
            "jdbc:postgresql://localhost:5433/ticket%70ay",
            "jdbc:postgresql://localhost:5433/%74%69%63%6b%65%74%70%61%79");

    for (String url : sharedUrls) {
      assertThatThrownBy(() -> TestDatabase.validateJdbcUrl(url))
          .as(url)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("shared ticketpay database");
    }
  }

  @Test
  void dedicated_database_urls_are_accepted() {
    List<String> dedicatedUrls =
        List.of(
            "jdbc:postgresql://localhost:5433/ticketpay_test",
            "jdbc:postgresql://localhost:5433/ticketpay_test?currentSchema=public",
            "jdbc:postgresql:ticketpay_test");

    for (String url : dedicatedUrls) {
      assertThat(TestDatabase.databaseName(url)).as(url).isEqualTo("ticketpay_test");
    }
  }

  @Test
  void ambiguous_or_non_postgres_urls_are_rejected() {
    List<String> malformedUrls =
        List.of(
            "jdbc:postgresql://localhost:5433",
            "jdbc:postgresql://localhost:5433/",
            "jdbc:postgresql://localhost:5433/tenant/ticketpay_test",
            "jdbc:postgresql:/ticketpay_test",
            "jdbc:mysql://localhost:3306/ticketpay_test");

    for (String url : malformedUrls) {
      assertThatThrownBy(() -> TestDatabase.databaseName(url))
          .as(url)
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void missing_credentials_are_rejected_before_property_registration() {
    DynamicPropertyRegistry registry = (name, supplier) -> {};

    assertThatThrownBy(
            () ->
                TestDatabase.register(
                    registry, Map.of(TestDatabase.URL_ENV, "jdbc:postgresql://db/ticketpay_test")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(TestDatabase.USER_ENV);

    assertThatThrownBy(
            () ->
                TestDatabase.register(
                    registry,
                    Map.of(
                        TestDatabase.URL_ENV,
                        "jdbc:postgresql://db/ticketpay_test",
                        TestDatabase.USER_ENV,
                        "test")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(TestDatabase.PASSWORD_ENV);
  }

  @Test
  void all_datasource_and_migration_aliases_are_pinned_to_the_validated_database() {
    Map<String, Object> properties = new HashMap<>();
    DynamicPropertyRegistry registry = (name, supplier) -> properties.put(name, supplier.get());

    TestDatabase.register(registry, validEnvironment());

    assertThat(properties)
        .containsEntry(TestDatabase.DATASOURCE_URL, DEDICATED_URL)
        .containsEntry(TestDatabase.DATASOURCE_USERNAME, USERNAME)
        .containsEntry(TestDatabase.DATASOURCE_PASSWORD, PASSWORD)
        .containsEntry(TestDatabase.HIKARI_JDBC_URL, DEDICATED_URL)
        .containsEntry(TestDatabase.HIKARI_USERNAME, USERNAME)
        .containsEntry(TestDatabase.HIKARI_PASSWORD, PASSWORD)
        .containsEntry(TestDatabase.FLYWAY_URL, DEDICATED_URL)
        .containsEntry(TestDatabase.FLYWAY_USER, USERNAME)
        .containsEntry(TestDatabase.FLYWAY_PASSWORD, PASSWORD);
  }

  @Test
  void hikari_jdbc_url_alias_to_the_shared_database_is_rejected() {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty(TestDatabase.DATASOURCE_URL, DEDICATED_URL)
            .withProperty(TestDatabase.HIKARI_JDBC_URL, SHARED_URL);

    assertThatThrownBy(
            () ->
                TestDatabase.verifyDataSource(
                    environment, hikariDataSource(DEDICATED_URL), validEnvironment()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(TestDatabase.HIKARI_JDBC_URL);
  }

  @Test
  void camel_case_hikari_jdbc_url_alias_to_the_shared_database_is_rejected() {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty(TestDatabase.DATASOURCE_URL, DEDICATED_URL)
            .withProperty(TestDatabase.HIKARI_JDBC_URL_CAMEL, SHARED_URL);

    assertThatThrownBy(
            () ->
                TestDatabase.verifyDataSource(
                    environment, hikariDataSource(DEDICATED_URL), validEnvironment()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(TestDatabase.HIKARI_JDBC_URL_CAMEL);
  }

  @Test
  void flyway_url_alias_to_the_shared_database_is_rejected() {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty(TestDatabase.DATASOURCE_URL, DEDICATED_URL)
            .withProperty(TestDatabase.FLYWAY_URL, SHARED_URL);

    assertThatThrownBy(
            () ->
                TestDatabase.verifyDataSource(
                    environment, hikariDataSource(DEDICATED_URL), validEnvironment()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(TestDatabase.FLYWAY_URL);
  }

  @Test
  void flyway_owned_datasource_to_the_shared_database_is_rejected() {
    Flyway flyway =
        flyway(driverDataSource(SHARED_URL, USERNAME, PASSWORD));

    assertThatThrownBy(() -> TestDatabase.verifyFlyway(flyway, validEnvironment()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Flyway JDBC URL");
  }

  @Test
  void flyway_owned_datasource_with_the_validated_connection_is_accepted() {
    Flyway flyway =
        flyway(driverDataSource(DEDICATED_URL, USERNAME, PASSWORD));

    TestDatabase.verifyFlyway(flyway, validEnvironment());
  }

  @Test
  void conflicting_alias_credentials_are_rejected() {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty(TestDatabase.DATASOURCE_URL, DEDICATED_URL)
            .withProperty(TestDatabase.HIKARI_USERNAME, "other_user")
            .withProperty(TestDatabase.FLYWAY_USER, "other_user");

    assertThatThrownBy(
            () ->
                TestDatabase.verifyDataSource(
                    environment, hikariDataSource(DEDICATED_URL), validEnvironment()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(TestDatabase.HIKARI_USERNAME);
  }

  @Test
  void mismatched_datasource_credentials_are_rejected() {
    assertThatThrownBy(
            () ->
                TestDatabase.verifyDataSource(
                    new MockEnvironment(),
                    hikariDataSource(DEDICATED_URL, "other_user", PASSWORD),
                    validEnvironment()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("username");
  }

  @Test
  void jndi_datasource_configuration_is_rejected() {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty(TestDatabase.JNDI_NAME, "java:comp/env/jdbc/ticketpay");

    assertThatThrownBy(
            () ->
                TestDatabase.verifyDataSource(
                    environment, hikariDataSource(DEDICATED_URL), validEnvironment()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(TestDatabase.JNDI_NAME);
  }

  @Test
  void hikari_data_source_class_name_is_rejected() {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty(
                TestDatabase.HIKARI_DATA_SOURCE_CLASS_NAME,
                "org.postgresql.ds.PGSimpleDataSource");

    assertThatThrownBy(
            () ->
                TestDatabase.verifyDataSource(
                    environment, hikariDataSource(DEDICATED_URL), validEnvironment()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(TestDatabase.HIKARI_DATA_SOURCE_CLASS_NAME);
  }

  @Test
  void hibernate_connection_url_is_rejected() {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty(TestDatabase.HIBERNATE_CONNECTION_URL, SHARED_URL);

    assertThatThrownBy(
            () ->
                TestDatabase.verifyDataSource(
                    environment, hikariDataSource(DEDICATED_URL), validEnvironment()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(TestDatabase.HIBERNATE_CONNECTION_URL);
  }

  @Test
  void hibernate_connection_datasource_is_rejected() {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty(
                TestDatabase.HIBERNATE_CONNECTION_DATASOURCE,
                "java:comp/env/jdbc/ticketpay");

    assertThatThrownBy(
            () ->
                TestDatabase.verifyDataSource(
                    environment, hikariDataSource(DEDICATED_URL), validEnvironment()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(TestDatabase.HIBERNATE_CONNECTION_DATASOURCE);
  }

  @Test
  void hibernate_connection_provider_class_is_rejected() {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty(
                TestDatabase.HIBERNATE_CONNECTION_PROVIDER_CLASS,
                "org.hibernate.engine.jdbc.connections.internal.DatasourceConnectionProviderImpl");

    assertThatThrownBy(
            () ->
                TestDatabase.verifyDataSource(
                    environment, hikariDataSource(DEDICATED_URL), validEnvironment()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(TestDatabase.HIBERNATE_CONNECTION_PROVIDER_CLASS);
  }

  @Test
  void unknown_datasource_type_is_rejected() {
    assertThatThrownBy(
            () ->
                TestDatabase.verifyDataSource(
                    new MockEnvironment(), mock(DataSource.class), validEnvironment()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("could not be verified");
  }

  @Test
  void missing_test_variables_are_rejected_before_a_datasource_is_accepted() {
    assertThatThrownBy(
            () ->
                TestDatabase.verifyDataSource(
                    new MockEnvironment(), hikariDataSource(DEDICATED_URL), Map.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(TestDatabase.URL_ENV);
  }

  @Test
  void partial_test_variables_are_rejected() {
    assertThatThrownBy(
            () ->
                TestDatabase.configuredConnection(
                    Map.of(TestDatabase.URL_ENV, DEDICATED_URL)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(TestDatabase.USER_ENV);
  }

  @Test
  void database_free_contexts_do_not_require_test_variables() {
    assertThat(TestDatabase.configuredConnection(Map.of())).isEmpty();
  }

  @Test
  void pinned_properties_take_precedence_over_shared_defaults_and_aliases() {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty(TestDatabase.DATASOURCE_URL, SHARED_URL)
            .withProperty(TestDatabase.HIKARI_JDBC_URL, SHARED_URL)
            .withProperty(TestDatabase.FLYWAY_URL, SHARED_URL);

    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "testDatabaseGuard",
                TestDatabase.pinnedProperties(
                    TestDatabase.requiredConnection(validEnvironment()))));

    assertThat(environment.getProperty(TestDatabase.DATASOURCE_URL)).isEqualTo(DEDICATED_URL);
    assertThat(environment.getProperty(TestDatabase.HIKARI_JDBC_URL)).isEqualTo(DEDICATED_URL);
    assertThat(environment.getProperty(TestDatabase.FLYWAY_URL)).isEqualTo(DEDICATED_URL);
    assertThat(environment.getProperty(TestDatabase.HIKARI_USERNAME)).isEqualTo(USERNAME);
    assertThat(environment.getProperty(TestDatabase.FLYWAY_PASSWORD)).isEqualTo(PASSWORD);
  }

  private static Map<String, String> validEnvironment() {
    return Map.of(
        TestDatabase.URL_ENV, DEDICATED_URL,
        TestDatabase.USER_ENV, USERNAME,
        TestDatabase.PASSWORD_ENV, PASSWORD);
  }

  private static HikariDataSource hikariDataSource(String url) {
    return hikariDataSource(url, USERNAME, PASSWORD);
  }

  private static HikariDataSource hikariDataSource(
      String url, String username, String password) {
    HikariDataSource dataSource = new HikariDataSource();
    dataSource.setJdbcUrl(url);
    dataSource.setUsername(username);
    dataSource.setPassword(password);
    return dataSource;
  }

  private static SimpleDriverDataSource driverDataSource(
      String url, String username, String password) {
    SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
    dataSource.setUrl(url);
    dataSource.setUsername(username);
    dataSource.setPassword(password);
    return dataSource;
  }

  private static Flyway flyway(DataSource dataSource) {
    Flyway flyway = mock(Flyway.class);
    Configuration configuration = mock(Configuration.class);
    when(flyway.getConfiguration()).thenReturn(configuration);
    when(configuration.getDataSource()).thenReturn(dataSource);
    return flyway;
  }
}
