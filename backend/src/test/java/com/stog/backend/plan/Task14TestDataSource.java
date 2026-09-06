package com.stog.backend.plan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;

final class Task14TestDataSource {
    private Task14TestDataSource() {
    }

    static DataSource create() throws IOException {
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
            throw new IllegalStateException("TEST_DB_PASSWORD is unavailable");
        }
        return new DriverManagerDataSource(password);
    }

    private static final class DriverManagerDataSource extends AbstractDataSource {
        private final String password;

        private DriverManagerDataSource(String password) {
            this.password = password;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/stog_backend_test",
                "stog_backend_test",
                password
            );
        }

        @Override
        public Connection getConnection(String username, String suppliedPassword) throws SQLException {
            return DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/stog_backend_test",
                username,
                suppliedPassword
            );
        }
    }
}
