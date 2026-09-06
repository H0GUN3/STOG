package com.stog.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
class TestDatabaseBootstrapTest {
    @Autowired
    private Environment environment;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void approvedTestProfileBootsOnTheDedicatedDatabaseAndIdentity() {
        assertThat(Arrays.asList(environment.getActiveProfiles())).containsExactly("test");
        assertThat(jdbc.sql("SELECT current_database()")
            .query(String.class)
            .single()).isEqualTo("stog_backend_test");
        assertThat(jdbc.sql("SELECT current_user")
            .query(String.class)
            .single()).isEqualTo("stog_backend_test");
    }
}
