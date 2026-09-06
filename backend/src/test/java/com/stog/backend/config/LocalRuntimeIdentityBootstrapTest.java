package com.stog.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;

class LocalRuntimeIdentityBootstrapTest {
    private static final String SPY_URL = "jdbc:stog-identity-spy:unreachable";

    @BeforeEach
    void resetSpy() {
        SpyDriver.connectionAttempts.set(0);
    }

    @Test
    void rejectedLocalIdentitiesFailBeforeAnyJdbcConnectionAttempt() {
        for (String identity : new String[] {
            "stog_runtime",
            "stog_migrator",
            "postgres",
            "stog_owner",
            "stog_backend_test",
            "STOG_APP",
            "",
            "${DB_USERNAME}"
        }) {
            assertEarlyGuardFailure(
                "local",
                identity,
                false,
                "Local runtime datasource identity"
            );
        }
    }

    @Test
    void everyExplicitLocalProfileMixFailsBeforeAnyJdbcConnectionAttempt() {
        for (String profiles : new String[] {
            "local,prod",
            "prod,local",
            "local,gcs-write",
            "local,migrator",
            "migrator,local",
            "local,test"
        }) {
            assertEarlyGuardFailure(
                profiles,
                "stog_app",
                false,
                profiles.contains("test")
                    ? "active profiles must be exactly [test]"
                    : "local runtime profile must run alone"
            );
        }
    }

    @Test
    void localEmbeddedAndAlternateIdentityPathsFailBeforeAnyJdbcConnectionAttempt() {
        assertEarlyGuardFailure(
            "local",
            "stog_app",
            false,
            "must not embed an identity",
            "spring.datasource.url=" + SPY_URL + "?user=stog_migrator"
        );
        for (String property : new String[] {
            "spring.datasource.jndi-name",
            "spring.datasource.hikari.username",
            "spring.datasource.hikari.password",
            "spring.datasource.hikari.jdbc-url",
            "spring.datasource.hikari.data-source-properties.user",
            "spring.datasource.hikari.data-source-properties.password"
        }) {
            assertEarlyGuardFailure(
                "local",
                "stog_app",
                false,
                "must not override",
                property + "=stog_migrator"
            );
        }
    }

    @Test
    void localDuplicateCommandLineIdentityAndEmbeddedUrlFailBeforeJdbc() {
        assertEarlyGuardFailure(
            "local",
            "stog_app",
            false,
            "exactly one resolved value",
            "duplicate:spring.datasource.username=stog_migrator"
        );
        assertEarlyGuardFailure(
            "local",
            "stog_app",
            false,
            "exactly one resolved value",
            "duplicate:spring.datasource.url=jdbc:stog-identity-spy://stog_migrator@unreachable"
        );
    }

    @Test
    void localFlywayOverrideFailsBeforeAnyJdbcConnectionAttempt() {
        assertEarlyGuardFailure(
            "local",
            "stog_app",
            true,
            "Flyway must remain disabled for the local runtime profile"
        );
    }

    @Test
    void exactLocalApplicationIdentityReachesJdbcWithFlywayDisabled() {
        assertThatThrownBy(() -> run("local", "stog_app", false))
            .hasRootCauseInstanceOf(SQLException.class)
            .hasRootCauseMessage("SPY_CONNECTION_ATTEMPT");
        assertThat(SpyDriver.connectionAttempts).hasValue(1);
    }

    @Test
    void rejectedMigratorModesFailBeforeAnyJdbcConnectionAttempt() {
        assertEarlyGuardFailure(
            "migrator",
            "stog_app",
            true,
            "identity must be exactly stog_migrator",
            "spring.flyway.user=stog_migrator"
        );
        assertEarlyGuardFailure(
            "migrator",
            "stog_migrator",
            true,
            "identity must be exactly stog_migrator",
            "spring.flyway.user=stog_app"
        );
        assertEarlyGuardFailure(
            "migrator,test",
            "stog_migrator",
            true,
            "active profiles must be exactly [test]",
            "spring.flyway.user=stog_migrator"
        );
        assertEarlyGuardFailure(
            "migrator",
            "stog_migrator",
            true,
            "must not embed an identity",
            "spring.flyway.user=stog_migrator",
            "spring.flyway.url=" + SPY_URL + "?user=stog_migrator"
        );
        for (String property : new String[] {
            "spring.datasource.jndi-name",
            "spring.datasource.hikari.username",
            "spring.datasource.hikari.password",
            "spring.datasource.hikari.jdbc-url",
            "spring.datasource.hikari.data-source-properties.user",
            "spring.datasource.hikari.data-source-properties.password"
        }) {
            assertEarlyGuardFailure(
                "migrator",
                "stog_migrator",
                true,
                "must not override",
                "spring.flyway.user=stog_migrator",
                property + "=stog_app"
            );
        }
    }

    @Test
    void migratorDuplicateCommandLineIdentityAndEmbeddedUrlFailBeforeJdbc() {
        assertEarlyGuardFailure(
            "migrator",
            "stog_migrator",
            true,
            "exactly one resolved value",
            "spring.flyway.user=stog_migrator",
            "duplicate:spring.flyway.user=stog_app"
        );
        assertEarlyGuardFailure(
            "migrator",
            "stog_migrator",
            true,
            "exactly one resolved value",
            "spring.flyway.user=stog_migrator",
            "duplicate:spring.datasource.url=jdbc:stog-identity-spy://stog_migrator@unreachable"
        );
    }

    @Test
    void exactStandaloneMigratorIdentityReachesFlywayJdbcBoundary() {
        assertThatThrownBy(() -> run(
            "migrator",
            "stog_migrator",
            true,
            "spring.flyway.user=stog_migrator"
        ))
            .hasRootCauseInstanceOf(SQLException.class)
            .hasRootCauseMessage("SPY_CONNECTION_ATTEMPT");
        assertThat(SpyDriver.connectionAttempts).hasValue(1);
    }

    private void assertEarlyGuardFailure(
        String profiles,
        String username,
        boolean flywayEnabled,
        String expectedMessage,
        String... overrides
    ) {
        SpyDriver.connectionAttempts.set(0);
        assertThatThrownBy(() -> run(profiles, username, flywayEnabled, overrides))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(expectedMessage);
        assertThat(SpyDriver.connectionAttempts).as(profiles + ":" + username).hasValue(0);
    }

    private void run(
        String profiles,
        String username,
        boolean flywayEnabled,
        String... overrides
    ) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("spring.config.name", "identity-safety-bootstrap");
        properties.put("spring.profiles.active", profiles);
        properties.put("spring.main.banner-mode", "off");
        properties.put("spring.main.log-startup-info", "false");
        properties.put("stog.test-database.enforce", "false");
        properties.put("spring.datasource.url", SPY_URL);
        properties.put("spring.datasource.username", username);
        properties.put("spring.datasource.password", "not-a-real-credential");
        properties.put("spring.datasource.driver-class-name", SpyDriver.class.getName());
        properties.put("spring.datasource.hikari.minimum-idle", "0");
        properties.put("spring.datasource.hikari.maximum-pool-size", "1");
        properties.put("spring.datasource.hikari.connection-timeout", "250");
        properties.put("spring.datasource.hikari.initialization-fail-timeout", "1");
        properties.put("spring.flyway.enabled", Boolean.toString(flywayEnabled));
        properties.put("spring.flyway.url", SPY_URL);
        properties.put("spring.flyway.password", "not-a-real-credential");
        properties.put("spring.flyway.driver-class-name", SpyDriver.class.getName());
        List<String> duplicateArguments = new ArrayList<>();
        for (String override : overrides) {
            String candidate = override;
            if (candidate.startsWith("duplicate:")) {
                candidate = candidate.substring("duplicate:".length());
                duplicateArguments.add("--" + candidate);
                continue;
            }
            int separator = candidate.indexOf('=');
            properties.put(
                candidate.substring(0, separator),
                candidate.substring(separator + 1)
            );
        }

        List<String> arguments = new ArrayList<>();
        properties.forEach((key, value) -> arguments.add("--" + key + "=" + value));
        arguments.addAll(duplicateArguments);

        SpringApplication application = new SpringApplication(BootstrapProbe.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setRegisterShutdownHook(false);
        application.run(arguments.toArray(String[]::new));
    }

    @SpringBootConfiguration
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
    static class BootstrapProbe {
        @Bean
        InitializingBean connectionBoundary(DataSource dataSource) {
            return () -> {
                try (Connection ignored = dataSource.getConnection()) {
                    // The spy driver always fails before a connection can be returned.
                }
            };
        }
    }

    public static final class SpyDriver implements Driver {
        private static final AtomicInteger connectionAttempts = new AtomicInteger();

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            if (!acceptsURL(url)) {
                return null;
            }
            connectionAttempts.incrementAndGet();
            throw new SQLException("SPY_CONNECTION_ATTEMPT");
        }

        @Override
        public boolean acceptsURL(String url) {
            return url != null && url.startsWith("jdbc:stog-identity-spy:");
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getLogger(SpyDriver.class.getName());
        }
    }
}
