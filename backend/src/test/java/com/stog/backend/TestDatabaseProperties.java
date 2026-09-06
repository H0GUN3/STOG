package com.stog.backend;

import com.stog.backend.config.TestDatabaseIsolationGuard;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

final class TestDatabaseProperties {
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/stog_backend_test";
    static final String USERNAME = "stog_backend_test";

    private TestDatabaseProperties() {
    }

    static String password() throws IOException {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve(".env.test"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("backend/.env.test was not found");
        }
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(current.resolve(".env.test"))) {
            properties.load(reader);
        }
        String password = properties.getProperty("TEST_DB_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("TEST_DB_PASSWORD must be configured in backend/.env.test");
        }
        TestDatabaseIsolationGuard.validate(
            List.of("test"), URL, USERNAME, URL, USERNAME
        );
        return password;
    }
}
