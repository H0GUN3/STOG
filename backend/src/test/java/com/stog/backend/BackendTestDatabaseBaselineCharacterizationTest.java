package com.stog.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class BackendTestDatabaseBaselineCharacterizationTest {
    @Test
    void unchangedBackendTestsFallBackToLocalEnvironmentBackedDatasource() throws IOException {
        String common = resourceText("application.yml");
        String local = resourceText("application-local.yml");
        String migrator = resourceText("application-migrator.yml");

        assertThat(common).contains("default: local");
        assertThat(local)
            .contains("import: optional:file:.env[.properties]")
            .contains("url: ${DB_URL}")
            .contains("username: ${DB_USERNAME}")
            .doesNotContain("MIGRATOR_DB_");
        assertThat(migrator)
            .contains("web-application-type: none")
            .contains("url: ${MIGRATOR_DB_URL}")
            .contains("username: ${MIGRATOR_DB_USERNAME}")
            .contains("user: ${MIGRATOR_DB_USERNAME}");
        assertThat(Files.exists(Path.of("src/main/resources/application-test.yml"))).isFalse();
    }

    private String resourceText(String name) throws IOException {
        return new ClassPathResource(name)
            .getContentAsString(StandardCharsets.UTF_8);
    }
}
