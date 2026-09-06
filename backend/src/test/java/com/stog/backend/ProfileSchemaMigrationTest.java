package com.stog.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
class ProfileSchemaMigrationTest {
    @Autowired
    private JdbcClient jdbc;

    @Test
    void flywayCreatesCanonicalSurveyProfileTables() {
        List<String> tables = jdbc.sql("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN (
                      'user_preferences',
                      'user_travel_styles',
                      'user_survey_responses'
                  )
                ORDER BY table_name
                """)
            .query(String.class)
            .list();

        assertThat(tables).containsExactly(
            "user_preferences",
            "user_survey_responses",
            "user_travel_styles"
        );
        assertThat(jdbc.sql("""
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version = '32' AND success
                """)
            .query(Integer.class)
            .single()).isEqualTo(1);
    }
}
