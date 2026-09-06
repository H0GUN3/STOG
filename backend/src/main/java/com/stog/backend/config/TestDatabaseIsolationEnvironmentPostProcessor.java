package com.stog.backend.config;

import java.util.Arrays;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

public final class TestDatabaseIsolationEnvironmentPostProcessor
    implements EnvironmentPostProcessor, Ordered {

    static final String ENFORCE_PROPERTY = "stog.test-database.enforce";

    @Override
    public void postProcessEnvironment(
        ConfigurableEnvironment environment,
        SpringApplication application
    ) {
        boolean testProfile = Arrays.stream(environment.getActiveProfiles())
            .anyMatch("test"::equals);
        if (!testProfile && !environment.getProperty(ENFORCE_PROPERTY, Boolean.class, false)) {
            return;
        }
        TestDatabaseIsolationGuard.validate(
            Arrays.asList(environment.getActiveProfiles()),
            environment.getProperty("spring.datasource.url"),
            environment.getProperty("spring.datasource.username"),
            environment.getProperty("spring.flyway.url"),
            environment.getProperty("spring.flyway.user")
        );
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
