package com.stog.backend.config;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

public final class LocalRuntimeMigrationSafetyEnvironmentPostProcessor
    implements EnvironmentPostProcessor, Ordered {

    static final String RUNTIME_IDENTITY = "stog_app";
    static final String MIGRATOR_IDENTITY = "stog_migrator";

    @Override
    public void postProcessEnvironment(
        ConfigurableEnvironment environment,
        SpringApplication application
    ) {
        if (isLocalRuntime(environment)) {
            validateLocalRuntime(environment);
            return;
        }
        if (hasActiveProfile(environment, "migrator")) {
            validateExplicitMigrator(environment);
        }
    }

    private void validateLocalRuntime(ConfigurableEnvironment environment) {
        String[] activeProfiles = environment.getActiveProfiles();
        if (activeProfiles.length > 0
            && !Arrays.equals(activeProfiles, new String[] {"local"})) {
            throw new IllegalStateException("The local runtime profile must run alone");
        }
        requireExactIdentity(
            "Local runtime datasource",
            resolvedSingleProperty(
                environment,
                "spring.datasource.username",
                "Local runtime datasource identity"
            ),
            RUNTIME_IDENTITY
        );
        rejectEmbeddedIdentity(resolvedSingleProperty(
            environment,
            "spring.datasource.url",
            "Local runtime datasource URL"
        ));
        rejectAlternateDatasourceProperties(environment);
        if (booleanProperty(environment, "spring.flyway.enabled", true)) {
            throw new IllegalStateException(
                "Flyway must remain disabled for the local runtime profile; "
                    + "forward migrations require the explicit migrator gate"
            );
        }
    }

    private void validateExplicitMigrator(ConfigurableEnvironment environment) {
        if (!Arrays.equals(environment.getActiveProfiles(), new String[] {"migrator"})) {
            throw new IllegalStateException("The migrator profile must run alone");
        }
        requireExactIdentity(
            "Migrator datasource",
            resolvedSingleProperty(
                environment,
                "spring.datasource.username",
                "Migrator datasource identity"
            ),
            MIGRATOR_IDENTITY
        );
        requireExactIdentity(
            "Flyway",
            resolvedSingleProperty(environment, "spring.flyway.user", "Flyway identity"),
            MIGRATOR_IDENTITY
        );
        String datasourceUrl = resolvedSingleProperty(
            environment,
            "spring.datasource.url",
            "Migrator datasource URL"
        );
        String flywayUrl = resolvedSingleProperty(
            environment,
            "spring.flyway.url",
            "Flyway URL"
        );
        rejectEmbeddedIdentity(datasourceUrl);
        rejectEmbeddedIdentity(flywayUrl);
        rejectAlternateDatasourceProperties(environment);
        if (!datasourceUrl.equals(flywayUrl)) {
            throw new IllegalStateException("Migrator datasource and Flyway targets must match");
        }
        if (!booleanProperty(environment, "spring.flyway.enabled", false)) {
            throw new IllegalStateException("Flyway must be enabled for the explicit migrator profile");
        }
    }

    private void rejectAlternateDatasourceProperties(
        ConfigurableEnvironment environment
    ) {
        for (String property : new String[] {
            "spring.datasource.jndi-name",
            "spring.datasource.hikari.username",
            "spring.datasource.hikari.password",
            "spring.datasource.hikari.jdbc-url",
            "spring.datasource.hikari.jdbcUrl",
            "spring.datasource.hikari.data-source-class-name",
            "spring.datasource.hikari.dataSourceClassName",
            "spring.datasource.hikari.data-source-properties.user",
            "spring.datasource.hikari.data-source-properties.username",
            "spring.datasource.hikari.data-source-properties.password",
            "spring.datasource.hikari.data-source-properties[user]",
            "spring.datasource.hikari.data-source-properties[username]",
            "spring.datasource.hikari.data-source-properties[password]",
            "spring.datasource.hikari.dataSourceProperties.user",
            "spring.datasource.hikari.dataSourceProperties.username",
            "spring.datasource.hikari.dataSourceProperties.password",
            "spring.datasource.hikari.dataSourceProperties[user]",
            "spring.datasource.hikari.dataSourceProperties[username]",
            "spring.datasource.hikari.dataSourceProperties[password]"
        }) {
            boolean present;
            try {
                present = environment.containsProperty(property);
                if (present) {
                    environment.getProperty(property);
                }
            } catch (RuntimeException error) {
                throw new IllegalStateException(property + " must be resolved", error);
            }
            if (present) {
                throw new IllegalStateException(
                    property + " must not override the guarded datasource"
                );
            }
        }
    }

    private void requireExactIdentity(String name, String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(name + " identity must be exactly " + expected);
        }
    }

    private String resolvedSingleProperty(
        ConfigurableEnvironment environment,
        String property,
        String name
    ) {
        String value;
        try {
            value = environment.getProperty(property);
        } catch (RuntimeException error) {
            throw new IllegalStateException(name + " must be resolved", error);
        }
        if (value == null || value.isBlank() || value.contains("${") || value.contains("}")) {
            throw new IllegalStateException(name + " must be resolved");
        }
        if (value.contains(",")) {
            throw new IllegalStateException(name + " must contain exactly one resolved value");
        }
        return value;
    }

    private boolean booleanProperty(
        ConfigurableEnvironment environment,
        String property,
        boolean defaultValue
    ) {
        try {
            return environment.getProperty(property, Boolean.class, defaultValue);
        } catch (RuntimeException error) {
            throw new IllegalStateException(property + " must be resolved", error);
        }
    }

    private void rejectEmbeddedIdentity(String url) {
        String decoded;
        try {
            decoded = URLDecoder.decode(url, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("Datasource URL is malformed", error);
        }
        if (decoded.contains(",")) {
            throw new IllegalStateException(
                "Datasource URL must contain exactly one resolved value"
            );
        }
        String normalized = decoded.toLowerCase(Locale.ROOT);
        int authorityStart = normalized.indexOf("://");
        if (authorityStart >= 0) {
            int authorityEnd = normalized.indexOf('/', authorityStart + 3);
            String authority = authorityEnd < 0
                ? normalized.substring(authorityStart + 3)
                : normalized.substring(authorityStart + 3, authorityEnd);
            if (authority.contains("@")) {
                throw new IllegalStateException("Datasource URL must not embed an identity");
            }
        }
        int queryStart = normalized.indexOf('?');
        if (queryStart >= 0) {
            for (String parameter : normalized.substring(queryStart + 1).split("[&;]")) {
                String key = parameter.substring(0, parameter.indexOf('=') < 0
                    ? parameter.length()
                    : parameter.indexOf('='));
                if (key.equals("user") || key.equals("username")) {
                    throw new IllegalStateException("Datasource URL must not embed an identity");
                }
            }
        }
    }

    private boolean isLocalRuntime(ConfigurableEnvironment environment) {
        String[] activeProfiles = environment.getActiveProfiles();
        if (activeProfiles.length > 0) {
            return Arrays.asList(activeProfiles).contains("local");
        }
        return Arrays.asList(environment.getDefaultProfiles()).contains("local");
    }

    private boolean hasActiveProfile(ConfigurableEnvironment environment, String profile) {
        return Arrays.asList(environment.getActiveProfiles()).contains(profile);
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
