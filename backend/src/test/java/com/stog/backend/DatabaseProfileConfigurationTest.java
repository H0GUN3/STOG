package com.stog.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class DatabaseProfileConfigurationTest {
    @Test
    void localAndMigratorProfilesLoadIgnoredCredentialsButProdDoesNot() throws IOException {
        String local = resourceText("application-local.yml");
        String migrator = resourceText("application-migrator.yml");
        String prod = resourceText("application-prod.yml");

        assertThat(local).contains("optional:file:.env[.properties]");
        assertThat(migrator).contains("optional:file:.env[.properties]");
        assertThat(prod).doesNotContain(".env");
    }

    @Test
    void localProfileContainsOnlyRuntimeDatasourceAndDisablesFlyway() throws IOException {
        String local = resourceText("application-local.yml");

        assertThat(local)
            .contains("flyway:")
            .contains("enabled: false")
            .contains("url: ${DB_URL}")
            .contains("username: ${DB_USERNAME}")
            .contains("password: ${DB_PASSWORD}")
            .doesNotContain("MIGRATOR_DB_");
    }

    @Test
    void explicitMigratorProfileUsesOnlyMigratorIdentityAndRunsWithoutWebRuntime() throws IOException {
        String migrator = resourceText("application-migrator.yml");

        assertThat(migrator)
            .contains("web-application-type: none")
            .contains("enabled: true")
            .contains("url: ${MIGRATOR_DB_URL}")
            .contains("username: ${MIGRATOR_DB_USERNAME}")
            .contains("user: ${MIGRATOR_DB_USERNAME}")
            .contains("password: ${MIGRATOR_DB_PASSWORD}")
            .doesNotContain("${DB_URL}")
            .doesNotContain("${DB_USERNAME}")
            .doesNotContain("${DB_PASSWORD}");
    }

    @Test
    void localRuntimeDoesNotCreateFlywayOrOpenTheDatasource() {
        DataSource dataSource = mock(DataSource.class);

        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FlywayAutoConfiguration.class))
            .withBean(DataSource.class, () -> dataSource)
            .withPropertyValues("spring.flyway.enabled=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean(Flyway.class);
                verifyNoInteractions(dataSource);
            });
    }

    @Test
    void directUnprofiledConfigDataLaunchActivatesOnlyTestWithoutLocalCredentials() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);

        ConfigDataEnvironmentPostProcessor.applyTo(environment);

        assertThat(environment.getActiveProfiles()).containsExactly("test");
        assertThat(environment.getProperty("spring.datasource.url"))
            .isEqualTo("jdbc:postgresql://127.0.0.1:5432/stog_backend_test");
        assertThat(environment.getProperty("spring.flyway.url"))
            .isEqualTo("jdbc:postgresql://127.0.0.1:5432/stog_backend_test");
        assertThat(environment.getProperty("spring.config.import"))
            .isEqualTo("optional:file:.env.test[.properties]");
    }

    @Test
    void testProfileUsesOnlyTheDedicatedTestDatabaseAndIdentity() throws IOException {
        String test = resourceText("application-test.yml");

        assertThat(test)
            .contains("jdbc:postgresql://127.0.0.1:5432/stog_backend_test")
            .contains("username: stog_backend_test")
            .contains("user: stog_backend_test")
            .contains("password: ${TEST_DB_PASSWORD}")
            .doesNotContain("DB_URL")
            .doesNotContain("MIGRATOR_DB_URL")
            .doesNotContain("CLOUD_DB_URL");
    }

    @Test
    void commonConfigurationKeepsFlywayEnabled() throws IOException {
        String common = resourceText("application.yml");

        assertThat(common)
            .contains("default: local")
            .contains("flyway:")
            .contains("enabled: true");
    }

    @Test
    void cloudAndProdProfilesDisableAutomaticSchemaChanges() throws IOException {
        for (String profile : new String[] {"application-cloud.yml", "application-prod.yml"}) {
            String configuration = resourceText(profile);
            assertThat(configuration)
                .contains("ddl-auto: none")
                .contains("flyway:")
                .contains("enabled: false");
        }
    }

    @Test
    void testGuardIsRegisteredBeforeSpringCreatesInfrastructure() throws IOException {
        assertThat(resourceText("META-INF/spring.factories"))
            .contains("org.springframework.boot.EnvironmentPostProcessor")
            .contains("TestDatabaseIsolationEnvironmentPostProcessor")
            .contains("LocalRuntimeMigrationSafetyEnvironmentPostProcessor");
    }

    @Test
    void cloudSourceDatasourceIsConfiguredReadOnly() throws IOException {
        assertThat(resourceText("application-cloud.yml"))
            .contains("read-only: true");
    }

    private String resourceText(String name) throws IOException {
        return new ClassPathResource(name)
            .getContentAsString(StandardCharsets.UTF_8);
    }
}
