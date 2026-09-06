package com.stog.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
class SavedPhotoSchemaMigrationTest {
    @Autowired
    private JdbcClient jdbc;

    @Test
    void flywayCreatesUserOwnedSavedPhotoRelation() {
        List<String> columns = jdbc.sql("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'saved_photos'
                ORDER BY ordinal_position
                """)
            .query(String.class)
            .list();
        List<String> constraints = jdbc.sql("""
                SELECT conname || ':' || contype::text
                FROM pg_constraint
                WHERE conrelid = 'saved_photos'::regclass
                """)
            .query(String.class)
            .list();
        List<String> indexes = jdbc.sql("""
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = 'saved_photos'
                """)
            .query(String.class)
            .list();

        assertThat(columns).containsExactly("user_id", "photo_id", "created_at");
        assertThat(constraints).contains(
            "saved_photos_pkey:p",
            "saved_photos_user_id_fkey:f",
            "saved_photos_photo_id_fkey:f"
        );
        assertThat(indexes).contains("saved_photos_user_created_photo_idx");
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM flyway_schema_history
                WHERE version = '34' AND success
                """)
            .query(Long.class)
            .single()).isEqualTo(1L);
    }
}
