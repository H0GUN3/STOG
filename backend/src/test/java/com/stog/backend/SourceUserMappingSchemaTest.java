package com.stog.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class SourceUserMappingSchemaTest {
    @Autowired
    private JdbcClient jdbc;

    @Test
    void storesCloudUuidAlongsideCanonicalUserId() {
        List<String> columns = jdbc.sql(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'source_user_mappings'
                ORDER BY ordinal_position
                """
            )
            .query(String.class)
            .list();

        assertThat(columns)
            .containsExactly("source_user_uuid", "user_id", "created_at");

        long userId = jdbc.sql(
                "INSERT INTO users (nickname) VALUES ('mapping-test') RETURNING id"
            )
            .query(Long.class)
            .single();
        UUID sourceUserUuid = UUID.randomUUID();

        jdbc.sql(
                """
                INSERT INTO source_user_mappings (source_user_uuid, user_id)
                VALUES (:sourceUserUuid, :userId)
                """
            )
            .param("sourceUserUuid", sourceUserUuid)
            .param("userId", userId)
            .update();

        String mappedUser = jdbc.sql(
                """
                SELECT source_user_uuid::text
                FROM source_user_mappings
                WHERE user_id = :userId
                """
            )
            .param("userId", userId)
            .query(String.class)
            .single();

        assertThat(mappedUser).isEqualTo(sourceUserUuid.toString());
    }
}
