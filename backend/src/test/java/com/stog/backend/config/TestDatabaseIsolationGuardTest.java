package com.stog.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TestDatabaseIsolationGuardTest {
    private static final String TEST_URL = "jdbc:postgresql://127.0.0.1:5432/stog_backend_test";
    private static final String TEST_USER = "stog_backend_test";

    @ParameterizedTest(name = "{0}")
    @MethodSource("forbiddenInputs")
    void rejectsForbiddenInputBeforeAConnectionOrMigrationAttempt(
        String label,
        List<String> profiles,
        String datasourceUrl,
        String datasourceUser,
        String flywayUrl,
        String flywayUser
    ) {
        AtomicInteger connectionAttempts = new AtomicInteger();

        assertThatThrownBy(() -> bootstrap(
            profiles,
            datasourceUrl,
            datasourceUser,
            flywayUrl,
            flywayUser,
            connectionAttempts
        ))
            .isInstanceOf(TestDatabaseIsolationGuard.Rejected.class)
            .hasMessageStartingWith(TestDatabaseIsolationGuard.REJECTION_CODE + ":");
        assertThat(connectionAttempts).hasValue(0);
    }

    @Test
    void approvedTestIdentityReachesOnlyTheBootstrapBoundary() {
        AtomicInteger connectionAttempts = new AtomicInteger();

        bootstrap(
            List.of("test"),
            TEST_URL,
            TEST_USER,
            TEST_URL,
            TEST_USER,
            connectionAttempts
        );

        assertThat(connectionAttempts).hasValue(1);
    }

    private static Stream<Arguments> forbiddenInputs() {
        return Stream.of(
            arguments("local canonical", List.of("test"),
                "jdbc:postgresql://localhost:5432/stog_canonical_dev", TEST_USER, TEST_URL, TEST_USER),
            arguments("Cloud canonical", List.of("test"),
                "jdbc:postgresql://localhost:5433/stog_canonical", TEST_USER, TEST_URL, TEST_USER),
            arguments("legacy local", List.of("test"),
                "jdbc:postgresql://localhost:5432/stog", TEST_USER, TEST_URL, TEST_USER),
            arguments("protected stog_0", List.of("test"),
                "jdbc:postgresql://localhost:5433/stog_0", TEST_USER, TEST_URL, TEST_USER),
            arguments("malformed URL", List.of("test"),
                "jdbc:postgresql:stog_canonical_dev", TEST_USER, TEST_URL, TEST_USER),
            arguments("mixed case and whitespace", List.of(" test "),
                " JDBC:POSTGRESQL://LOCALHOST:5432/STOG_CANONICAL_DEV ", TEST_USER, TEST_URL, TEST_USER),
            arguments("alternate loopback host", List.of("test"),
                "jdbc:postgresql://localhost:5432/stog_backend_test", TEST_USER, TEST_URL, TEST_USER),
            arguments("query parameters", List.of("test"),
                TEST_URL + "?currentSchema=public", TEST_USER, TEST_URL, TEST_USER),
            arguments("URL alias", List.of("test"),
                "jdbc:postgresql://127.0.0.1/stog_backend_test", TEST_USER, TEST_URL, TEST_USER),
            arguments("Cloud SQL socket factory URL", List.of("test"),
                TEST_URL + "?socketFactory=com.google.cloud.sql.postgres.SocketFactory&cloudSqlInstance=x", TEST_USER,
                TEST_URL, TEST_USER),
            arguments("runtime identity mismatch", List.of("test"),
                TEST_URL, "stog_app", TEST_URL, TEST_USER),
            arguments("migration identity mismatch", List.of("test"),
                TEST_URL, TEST_USER, TEST_URL, "stog_migrator"),
            arguments("migration target mismatch", List.of("test"),
                TEST_URL, TEST_USER, "jdbc:postgresql://127.0.0.1:5432/stog_canonical_dev", TEST_USER),
            arguments("stale local profile", List.of("test", "local"),
                TEST_URL, TEST_USER, TEST_URL, TEST_USER),
            arguments("stale prod profile", List.of("test", "prod"),
                TEST_URL, TEST_USER, TEST_URL, TEST_USER),
            arguments("stale cloud profile", List.of("test", "cloud"),
                TEST_URL, TEST_USER, TEST_URL, TEST_USER),
            arguments("stale gcs write profile", List.of("test", "gcs-write"),
                TEST_URL, TEST_USER, TEST_URL, TEST_USER),
            arguments("other write profile", List.of("test", "cloud-write"),
                TEST_URL, TEST_USER, TEST_URL, TEST_USER),
            arguments("missing test profile", List.of(),
                TEST_URL, TEST_USER, TEST_URL, TEST_USER)
        );
    }

    private static Arguments arguments(
        String label,
        List<String> profiles,
        String datasourceUrl,
        String datasourceUser,
        String flywayUrl,
        String flywayUser
    ) {
        return Arguments.of(label, profiles, datasourceUrl, datasourceUser, flywayUrl, flywayUser);
    }

    private static void bootstrap(
        List<String> profiles,
        String datasourceUrl,
        String datasourceUser,
        String flywayUrl,
        String flywayUser,
        AtomicInteger connectionAttempts
    ) {
        TestDatabaseIsolationGuard.validate(
            profiles,
            datasourceUrl,
            datasourceUser,
            flywayUrl,
            flywayUser
        );
        connectionAttempts.incrementAndGet();
    }
}
