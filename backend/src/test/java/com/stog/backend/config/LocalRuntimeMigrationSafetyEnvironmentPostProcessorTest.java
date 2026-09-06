package com.stog.backend.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

class LocalRuntimeMigrationSafetyEnvironmentPostProcessorTest {
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/stog_canonical_dev";

    private final LocalRuntimeMigrationSafetyEnvironmentPostProcessor processor =
        new LocalRuntimeMigrationSafetyEnvironmentPostProcessor();

    @Test
    void localRuntimeAcceptsOnlyExactDocumentedApplicationIdentityWithDisabledFlyway() {
        assertThatCode(() -> process(localRuntime("stog_app", URL, false)))
            .doesNotThrowAnyException();
    }

    @Test
    void defaultLocalRuntimeUsesTheSameIdentityBoundary() {
        MockEnvironment environment = new MockEnvironment();
        environment.setDefaultProfiles("local");
        environment.setProperty("spring.datasource.username", "stog_app");
        environment.setProperty("spring.datasource.url", URL);
        environment.setProperty("spring.flyway.enabled", "false");

        assertThatCode(() -> process(environment)).doesNotThrowAnyException();
    }

    @Test
    void localRuntimeRejectsRuntimeAliasMigratorOwnerTestEmptyPlaceholderAndCaseVariants() {
        for (String identity : List.of(
            "stog_runtime",
            "stog_migrator",
            "postgres",
            "stog_owner",
            "stog_backend_test",
            "STOG_APP",
            "jdbc:postgresql://stog_app@127.0.0.1/stog_canonical_dev"
        )) {
            assertThatThrownBy(() -> process(localRuntime(identity, URL, false)))
                .as(identity)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("identity must be exactly stog_app");
        }
        for (String identity : List.of(" ", "${DB_USERNAME}")) {
            assertThatThrownBy(() -> process(localRuntime(identity, URL, false)))
                .as(identity)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("identity must be resolved");
        }
    }

    @Test
    void localRuntimeRejectsEmptyAndUnresolvedDatasourceUrls() {
        for (String url : List.of(
            " ",
            "${DB_URL}",
            "jdbc:postgresql://${DB_HOST}/stog_canonical_dev"
        )) {
            assertThatThrownBy(() -> process(localRuntime("stog_app", url, false)))
                .as(url)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be resolved");
        }
    }

    @Test
    void localRuntimeRejectsJdbcEmbeddedIdentityVariants() {
        for (String url : List.of(
            "jdbc:postgresql://stog_migrator@127.0.0.1:5432/stog_canonical_dev",
            URL + "?user=stog_migrator",
            URL + "?USER=stog_app",
            URL + "?%75ser=stog_migrator",
            URL + "?sslmode=disable&username=stog_app"
        )) {
            assertThatThrownBy(() -> process(localRuntime("stog_app", url, false)))
                .as(url)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not embed an identity");
        }
    }

    @Test
    void localRuntimeRejectsHikariJndiAndDriverPropertyIdentityOverrides() {
        assertAlternateDatasourcePropertiesRejected(false);
    }

    @Test
    void localRuntimeRejectsDuplicateAndCommaCombinedIdentityOrUrlValues() {
        for (String identity : List.of("stog_app,stog_app", "stog_app,stog_migrator")) {
            assertThatThrownBy(() -> process(localRuntime(identity, URL, false)))
                .as(identity)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one resolved value");
        }
        for (String url : List.of(
            URL + "," + URL,
            URL + ",jdbc:postgresql://stog_migrator@127.0.0.1/stog_canonical_dev",
            URL + "%2Cjdbc:postgresql://stog_migrator@127.0.0.1/stog_canonical_dev"
        )) {
            assertThatThrownBy(() -> process(localRuntime("stog_app", url, false)))
                .as(url)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one resolved value");
        }
    }

    @Test
    void localRuntimeRejectsAnAttemptToEnableFlywayBeforeInfrastructureStarts() {
        assertThatThrownBy(() -> process(localRuntime("stog_app", URL, true)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Flyway must remain disabled for the local runtime profile");
    }

    @Test
    void explicitLocalProfileMustRunAloneRegardlessOfProfileOrder() {
        for (String[] profiles : List.of(
            new String[] {"local", "prod"},
            new String[] {"prod", "local"},
            new String[] {"local", "gcs-write"},
            new String[] {"local", "migrator"},
            new String[] {"migrator", "local"},
            new String[] {"local", "test"}
        )) {
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles(profiles);
            assertThatThrownBy(() -> process(environment))
                .as(String.join(",", profiles))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local runtime profile must run alone");
        }
    }

    @Test
    void canonicalProdGcsWritePathAndFailClosedProfilesRemainOutsideTheLocalGuard() {
        for (String[] profiles : List.of(
            new String[] {"prod", "gcs-write"},
            new String[] {"test"},
            new String[] {"cloud"}
        )) {
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles(profiles);
            assertThatCode(() -> process(environment))
                .as(String.join(",", profiles))
                .doesNotThrowAnyException();
        }
    }

    @Test
    void explicitMigratorProfileAcceptsOnlyMigratorIdentityAndMatchingTargets() {
        assertThatCode(() -> process(migrator())).doesNotThrowAnyException();
    }

    @Test
    void explicitMigratorProfileRejectsApplicationIdentityMixedProfilesAndTargetMismatch() {
        MockEnvironment applicationIdentity = migrator();
        applicationIdentity.setProperty("spring.datasource.username", "stog_app");
        assertThatThrownBy(() -> process(applicationIdentity))
            .hasMessageContaining("identity must be exactly stog_migrator");

        MockEnvironment flywayIdentity = migrator();
        flywayIdentity.setProperty("spring.flyway.user", "stog_app");
        assertThatThrownBy(() -> process(flywayIdentity))
            .hasMessageContaining("identity must be exactly stog_migrator");

        MockEnvironment targetMismatch = migrator();
        targetMismatch.setProperty("spring.flyway.url", URL + "_other");
        assertThatThrownBy(() -> process(targetMismatch))
            .hasMessageContaining("targets must match");

        MockEnvironment mixed = migrator();
        mixed.setActiveProfiles("migrator", "test");
        assertThatThrownBy(() -> process(mixed))
            .hasMessageContaining("must run alone");
    }

    @Test
    void explicitMigratorRejectsHikariJndiAndDriverPropertyIdentityOverrides() {
        assertAlternateDatasourcePropertiesRejected(true);
    }

    @Test
    void explicitMigratorRejectsDuplicateIdentityAndUrlValues() {
        for (String property : List.of("spring.datasource.username", "spring.flyway.user")) {
            MockEnvironment environment = migrator();
            environment.setProperty(property, "stog_migrator,stog_migrator");
            assertThatThrownBy(() -> process(environment))
                .as(property)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one resolved value");
        }
        for (String property : List.of("spring.datasource.url", "spring.flyway.url")) {
            MockEnvironment environment = migrator();
            environment.setProperty(
                property,
                URL + ",jdbc:postgresql://stog_migrator@127.0.0.1/stog_canonical_dev"
            );
            assertThatThrownBy(() -> process(environment))
                .as(property)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one resolved value");
        }
    }

    @Test
    void explicitMigratorRejectsEmbeddedDatasourceAndFlywayIdentities() {
        for (String property : List.of("spring.datasource.url", "spring.flyway.url")) {
            MockEnvironment environment = migrator();
            environment.setProperty(property, URL + "?user=stog_migrator");
            assertThatThrownBy(() -> process(environment))
                .as(property)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not embed an identity");
        }
    }

    private void assertAlternateDatasourcePropertiesRejected(boolean migrator) {
        for (String property : List.of(
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
        )) {
            MockEnvironment environment = migrator
                ? migrator()
                : localRuntime("stog_app", URL, false);
            environment.setProperty(property, migrator ? "stog_app" : "stog_migrator");
            assertThatThrownBy(() -> process(environment))
                .as(property)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not override");
        }
    }

    @Test
    void hikariCredentialOverridesAreRejectedEvenWhenBlankOrEqualToCanonicalCredential() {
        for (boolean migrator : List.of(false, true)) {
            for (String property : List.of(
                "spring.datasource.hikari.password",
                "spring.datasource.hikari.data-source-properties.password",
                "spring.datasource.hikari.data-source-properties[password]",
                "spring.datasource.hikari.dataSourceProperties.password",
                "spring.datasource.hikari.dataSourceProperties[password]"
            )) {
                for (String value : List.of("", "not-a-real-credential")) {
                    MockEnvironment environment = migrator
                        ? migrator()
                        : localRuntime("stog_app", URL, false);
                    environment.setProperty(property, value);
                    assertThatThrownBy(() -> process(environment))
                        .as((migrator ? "migrator:" : "local:") + property)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("must not override");
                }
            }
        }
    }

    private MockEnvironment localRuntime(String username, String url, boolean flywayEnabled) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        environment.setProperty("spring.datasource.username", username);
        environment.setProperty("spring.datasource.url", url);
        environment.setProperty("spring.flyway.enabled", Boolean.toString(flywayEnabled));
        return environment;
    }

    private MockEnvironment migrator() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("migrator");
        environment.setProperty("spring.datasource.username", "stog_migrator");
        environment.setProperty("spring.datasource.url", URL);
        environment.setProperty("spring.flyway.user", "stog_migrator");
        environment.setProperty("spring.flyway.url", URL);
        environment.setProperty("spring.flyway.enabled", "true");
        return environment;
    }

    private void process(MockEnvironment environment) {
        processor.postProcessEnvironment(environment, new SpringApplication(Object.class));
    }
}
