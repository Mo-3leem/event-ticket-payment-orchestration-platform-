package com.ticketpay;

import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

public final class TestDatasourceGuardInitializer
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {

  static final String PROPERTY_SOURCE_NAME = "testDatabaseGuard";

  private final Map<String, String> testEnvironment;

  public TestDatasourceGuardInitializer() {
    this(System.getenv());
  }

  TestDatasourceGuardInitializer(Map<String, String> testEnvironment) {
    this.testEnvironment = testEnvironment;
  }

  @Override
  public void initialize(ConfigurableApplicationContext context) {
    ConfigurableEnvironment environment = context.getEnvironment();
    TestDatabase.configuredConnection(testEnvironment)
        .ifPresent(
            connection ->
                environment
                    .getPropertySources()
                    .addFirst(
                        new MapPropertySource(
                            PROPERTY_SOURCE_NAME, TestDatabase.pinnedProperties(connection))));
    context
        .getBeanFactory()
        .addBeanPostProcessor(new DatasourceGuard(environment, testEnvironment));
  }

  private static final class DatasourceGuard implements BeanPostProcessor, PriorityOrdered {

    private final ConfigurableEnvironment environment;
    private final Map<String, String> testEnvironment;

    private DatasourceGuard(
        ConfigurableEnvironment environment, Map<String, String> testEnvironment) {
      this.environment = environment;
      this.testEnvironment = testEnvironment;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
      if (bean instanceof DataSource dataSource) {
        TestDatabase.verifyDataSource(environment, dataSource, testEnvironment);
      }
      if (bean instanceof Flyway flyway) {
        TestDatabase.verifyFlyway(flyway, testEnvironment);
      }
      return bean;
    }

    @Override
    public int getOrder() {
      return Ordered.LOWEST_PRECEDENCE;
    }
  }
}
