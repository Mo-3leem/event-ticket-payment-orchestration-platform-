package com.ticketpay;

import com.zaxxer.hikari.HikariDataSource;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.AbstractDriverBasedDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;

final class TestDatabase {

  static final String URL_ENV = "TEST_DATABASE_URL";
  static final String USER_ENV = "TEST_DATABASE_USER";
  static final String PASSWORD_ENV = "TEST_DATABASE_PASSWORD";

  static final String DATASOURCE_URL = "spring.datasource.url";
  static final String DATASOURCE_USERNAME = "spring.datasource.username";
  static final String DATASOURCE_PASSWORD = "spring.datasource.password";
  static final String HIKARI_JDBC_URL = "spring.datasource.hikari.jdbc-url";
  static final String HIKARI_JDBC_URL_CAMEL = "spring.datasource.hikari.jdbcUrl";
  static final String HIKARI_USERNAME = "spring.datasource.hikari.username";
  static final String HIKARI_PASSWORD = "spring.datasource.hikari.password";
  static final String HIKARI_DATA_SOURCE_CLASS_NAME =
      "spring.datasource.hikari.data-source-class-name";
  static final String HIKARI_DATA_SOURCE_CLASS_NAME_CAMEL =
      "spring.datasource.hikari.dataSourceClassName";
  static final String FLYWAY_URL = "spring.flyway.url";
  static final String FLYWAY_USER = "spring.flyway.user";
  static final String FLYWAY_PASSWORD = "spring.flyway.password";
  static final String JNDI_NAME = "spring.datasource.jndi-name";
  static final String HIBERNATE_CONNECTION_URL =
      "spring.jpa.properties.hibernate.connection.url";
  static final String JPA_CONNECTION_URL =
      "spring.jpa.properties.jakarta.persistence.jdbc.url";
  static final String HIBERNATE_CONNECTION_DATASOURCE =
      "spring.jpa.properties.hibernate.connection.datasource";
  static final String HIBERNATE_CONNECTION_PROVIDER_CLASS =
      "spring.jpa.properties.hibernate.connection.provider_class";

  private static final String POSTGRES_PREFIX = "jdbc:postgresql:";
  private static final String SHARED_DATABASE = "ticketpay";

  private TestDatabase() {}

  static void register(DynamicPropertyRegistry registry) {
    register(registry, System.getenv());
  }

  static void register(DynamicPropertyRegistry registry, Map<String, String> environment) {
    pinnedProperties(requiredConnection(environment))
        .forEach((name, value) -> registry.add(name, () -> value));
  }

  static Optional<TestConnection> configuredConnection(Map<String, String> environment) {
    String url = environment.get(URL_ENV);
    String username = environment.get(USER_ENV);
    String password = environment.get(PASSWORD_ENV);
    if (!hasText(url) && !hasText(username) && !hasText(password)) {
      return Optional.empty();
    }
    return Optional.of(requiredConnection(environment));
  }

  static TestConnection requiredConnection(Map<String, String> environment) {
    String url = required(URL_ENV, environment.get(URL_ENV)).trim();
    String username = required(USER_ENV, environment.get(USER_ENV));
    String password = required(PASSWORD_ENV, environment.get(PASSWORD_ENV));
    validateJdbcUrl(url);
    return new TestConnection(url, username, password);
  }

  static Map<String, Object> pinnedProperties(TestConnection connection) {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(DATASOURCE_URL, connection.url());
    properties.put(DATASOURCE_USERNAME, connection.username());
    properties.put(DATASOURCE_PASSWORD, connection.password());
    properties.put(HIKARI_JDBC_URL, connection.url());
    properties.put(HIKARI_USERNAME, connection.username());
    properties.put(HIKARI_PASSWORD, connection.password());
    properties.put(FLYWAY_URL, connection.url());
    properties.put(FLYWAY_USER, connection.username());
    properties.put(FLYWAY_PASSWORD, connection.password());
    return properties;
  }

  static void verifyDataSource(Environment environment, DataSource dataSource) {
    verifyDataSource(environment, dataSource, System.getenv());
  }

  static void verifyDataSource(
      Environment environment, DataSource dataSource, Map<String, String> testEnvironment) {
    rejectAlternateConnectionPaths(environment);

    if (dataSource instanceof HikariDataSource hikari) {
      verifyConnection(
          "Hikari",
          hikari.getJdbcUrl(),
          hikari.getUsername(),
          hikari.getPassword(),
          environment,
          testEnvironment);
    } else if (dataSource instanceof AbstractDriverBasedDataSource driverBased) {
      verifyConnection(
          "datasource",
          driverBased.getUrl(),
          driverBased.getUsername(),
          driverBased.getPassword(),
          environment,
          testEnvironment);
    } else {
      throw new IllegalStateException(
          "DataSource type "
              + dataSource.getClass().getName()
              + " could not be verified against the validated "
              + URL_ENV
              + " target; refusing to use an unverified datasource");
    }
  }

  static void verifyFlyway(Flyway flyway, Map<String, String> testEnvironment) {
    Configuration configuration = flyway.getConfiguration();
    if (configuration == null) {
      return;
    }
    DataSource dataSource = configuration.getDataSource();
    if (dataSource instanceof HikariDataSource hikari) {
      verifyConnection(
          "Flyway",
          hikari.getJdbcUrl(),
          hikari.getUsername(),
          hikari.getPassword(),
          null,
          testEnvironment);
    } else if (dataSource instanceof AbstractDriverBasedDataSource driverBased) {
      verifyConnection(
          "Flyway",
          driverBased.getUrl(),
          driverBased.getUsername(),
          driverBased.getPassword(),
          null,
          testEnvironment);
    } else if (configuration.getUrl() != null) {
      TestConnection expected = requiredConnection(testEnvironment);
      requireMatch("Flyway JDBC URL", configuration.getUrl(), expected.url());
      requireMatch("Flyway username", configuration.getUser(), expected.username());
      requireMatch("Flyway password", configuration.getPassword(), expected.password());
    } else if (dataSource == null) {
      throw new IllegalStateException(
          "Flyway has no datasource; " + URL_ENV + " must be the only migration target");
    } else {
      throw new IllegalStateException(
          "Flyway datasource could not be verified against the validated "
              + URL_ENV
              + "; refusing to migrate");
    }
  }

  private static void verifyConnection(
      String source,
      String url,
      String username,
      String password,
      Environment environment,
      Map<String, String> testEnvironment) {
    TestConnection expected = requiredConnection(testEnvironment);
    requireMatch(source + " JDBC URL", url, expected.url());
    requireMatch(source + " username", username, expected.username());
    requireMatch(source + " password", password, expected.password());
    if (environment != null) {
      requireAliasMatch(environment, HIKARI_JDBC_URL, expected.url());
      requireAliasMatch(environment, HIKARI_JDBC_URL_CAMEL, expected.url());
      requireAliasMatch(environment, HIKARI_USERNAME, expected.username());
      requireAliasMatch(environment, HIKARI_PASSWORD, expected.password());
      requireAliasMatch(environment, FLYWAY_URL, expected.url());
      requireAliasMatch(environment, FLYWAY_USER, expected.username());
      requireAliasMatch(environment, FLYWAY_PASSWORD, expected.password());
    }
  }

  private static void rejectAlternateConnectionPaths(Environment environment) {
    if (hasText(environment.getProperty(JNDI_NAME))) {
      throw new IllegalStateException(
          JNDI_NAME
              + " must not be used in tests; "
              + URL_ENV
              + " must be the only connection source");
    }
    if (firstNonBlank(
            environment, HIKARI_DATA_SOURCE_CLASS_NAME, HIKARI_DATA_SOURCE_CLASS_NAME_CAMEL)
        != null) {
      throw new IllegalStateException(
          HIKARI_DATA_SOURCE_CLASS_NAME
              + " must not be used in tests; it can bypass the validated "
              + URL_ENV
              + " target");
    }
    String hibernateProperty =
        firstNonBlankProperty(
            environment,
            HIBERNATE_CONNECTION_URL,
            JPA_CONNECTION_URL,
            HIBERNATE_CONNECTION_DATASOURCE,
            HIBERNATE_CONNECTION_PROVIDER_CLASS);
    if (hibernateProperty != null) {
      throw new IllegalStateException(
          hibernateProperty
              + " must not be used in tests; use "
              + URL_ENV
              + " as the only connection source");
    }
  }

  private static void requireAliasMatch(
      Environment environment, String property, String expected) {
    String value = environment.getProperty(property);
    if (hasText(value)) {
      requireMatch(property, value, expected);
    }
  }

  private static void requireMatch(String property, String actual, String expected) {
    if (actual == null || !actual.equals(expected)) {
      throw new IllegalStateException(
          property
              + " does not match the validated "
              + URL_ENV
              + "/"
              + USER_ENV
              + "/"
              + PASSWORD_ENV
              + " configuration; refusing to open a datasource outside the dedicated test"
              + " database");
    }
  }

  private static String firstNonBlank(Environment environment, String... properties) {
    for (String property : properties) {
      String value = environment.getProperty(property);
      if (hasText(value)) {
        return value;
      }
    }
    return null;
  }

  private static String firstNonBlankProperty(Environment environment, String... properties) {
    for (String property : properties) {
      if (hasText(environment.getProperty(property))) {
        return property;
      }
    }
    return null;
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  static String required(String name, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          name
              + " is required for database integration tests; point it at a dedicated"
              + " PostgreSQL database, never the shared ticketpay database");
    }
    return value;
  }

  static void validateJdbcUrl(String jdbcUrl) {
    String database = databaseName(jdbcUrl);
    if (SHARED_DATABASE.equalsIgnoreCase(database)) {
      throw new IllegalStateException(
          URL_ENV
              + " must not target the shared ticketpay database; use a dedicated test database");
    }
  }

  static String databaseName(String jdbcUrl) {
    if (jdbcUrl == null || jdbcUrl.isBlank()) {
      throw new IllegalStateException(URL_ENV + " is required for database integration tests");
    }

    String url = jdbcUrl.trim();
    if (!url.startsWith(POSTGRES_PREFIX)) {
      throw new IllegalStateException(URL_ENV + " must be a PostgreSQL JDBC URL");
    }

    String remainder = url.substring(POSTGRES_PREFIX.length());
    int query = firstIndexOf(remainder, '?', '#');
    if (query >= 0) {
      remainder = remainder.substring(0, query);
    }

    String database;
    if (remainder.startsWith("//")) {
      int path = remainder.indexOf('/', 2);
      if (path < 0) {
        throw ambiguous();
      }
      database = remainder.substring(path + 1);
    } else {
      database = remainder;
    }

    if (database.isBlank() || database.contains("/")) {
      throw ambiguous();
    }

    try {
      database = URLDecoder.decode(database, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException malformedEncoding) {
      throw ambiguous();
    }

    if (database.isBlank() || database.contains("/") || database.contains("?")
        || database.contains("#")) {
      throw ambiguous();
    }
    return database;
  }

  private static int firstIndexOf(String value, char first, char second) {
    int firstIndex = value.indexOf(first);
    int secondIndex = value.indexOf(second);
    if (firstIndex < 0) {
      return secondIndex;
    }
    if (secondIndex < 0) {
      return firstIndex;
    }
    return Math.min(firstIndex, secondIndex);
  }

  private static IllegalStateException ambiguous() {
    return new IllegalStateException(
        URL_ENV
            + " must name exactly one PostgreSQL database, for example"
            + " jdbc:postgresql://localhost:5432/ticketpay_test");
  }

  record TestConnection(String url, String username, String password) {}
}
