package com.stog.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
class AuthSchemaMigrationTest {
    @Autowired
    private JdbcClient jdbc;

    @Test
    void flywayCreatesAuthenticationTables() {
        List<String> tables = jdbc.sql(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('users', 'auth_accounts')
                ORDER BY table_name
                """
            )
            .query(String.class)
            .list();

        assertThat(tables).containsExactly("auth_accounts", "users");

        Integer migrationCount = jdbc.sql(
                """
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version = '1' AND success
                """
            )
            .query(Integer.class)
            .single();

        assertThat(migrationCount).isEqualTo(1);
    }
}
