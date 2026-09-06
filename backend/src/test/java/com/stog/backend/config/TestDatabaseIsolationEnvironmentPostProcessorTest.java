package com.stog.backend.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

class TestDatabaseIsolationEnvironmentPostProcessorTest {
    private final TestDatabaseIsolationEnvironmentPostProcessor processor =
        new TestDatabaseIsolationEnvironmentPostProcessor();

    @Test
    void approvedEnvironmentPassesBeforeBootstrap() {
        MockEnvironment environment = environment(
            "test",
            TestDatabaseIsolationGuard.APPROVED_URL,
            TestDatabaseIsolationGuard.APPROVED_USERNAME,
            TestDatabaseIsolationGuard.APPROVED_URL,
            TestDatabaseIsolationGuard.APPROVED_USERNAME
        );

        assertThatCode(() -> processor.postProcessEnvironment(
            environment, new SpringApplication(Object.class)
        )).doesNotThrowAnyException();
    }

    @Test
    void enforcedStaleProdEnvironmentIsRejectedBeforeBootstrap() {
        MockEnvironment environment = environment(
            "prod",
            TestDatabaseIsolationGuard.APPROVED_URL,
            TestDatabaseIsolationGuard.APPROVED_USERNAME,
            TestDatabaseIsolationGuard.APPROVED_URL,
            TestDatabaseIsolationGuard.APPROVED_USERNAME
        );
        environment.setProperty("stog.test-database.enforce", "true");

        assertThatThrownBy(() -> processor.postProcessEnvironment(
            environment, new SpringApplication(Object.class)
        ))
            .isInstanceOf(TestDatabaseIsolationGuard.Rejected.class)
            .hasMessage(TestDatabaseIsolationGuard.REJECTION_CODE
                + ": active profiles must be exactly [test]");
    }

    private static MockEnvironment environment(
        String profile,
        String datasourceUrl,
        String datasourceUsername,
        String flywayUrl,
        String flywayUsername
    ) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        environment.setProperty("spring.datasource.url", datasourceUrl);
        environment.setProperty("spring.datasource.username", datasourceUsername);
        environment.setProperty("spring.flyway.url", flywayUrl);
        environment.setProperty("spring.flyway.user", flywayUsername);
        return environment;
    }
}
