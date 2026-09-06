package com.stog.backend.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TravelGuideAiDatabaseImmutabilityTest {
    private DataSource dataSource;

    @BeforeEach
    void configureDataSource() throws Exception {
        dataSource = Task14TestDataSource.create();
    }

    @Test
    void freshV23RejectsEveryHistoryIdentityAndLedgerMutation() throws Exception {
        // Given
        try (DisposableSchema schema = DisposableSchema.create(dataSource)) {
            schema.migrate(null);
            schema.fixture();

            // When / Then
            assertThat(schema.version()).isEqualTo(1);
            schema.assertRejected("UPDATE itinerary_changes SET payload='{}' WHERE id=9501", "append only");
            schema.assertRejected("DELETE FROM itinerary_changes WHERE id=9501", "append only");
            schema.assertRejected("UPDATE travel_guide_ai_proposals SET base_version=8 WHERE id='00000000-0000-0000-0000-000000009601'", "immutable");
            schema.assertRejected("UPDATE travel_guide_ai_proposals SET applied_version=9 WHERE id='00000000-0000-0000-0000-000000009601'", "single apply receipt");
            schema.assertRejected("DELETE FROM travel_guide_ai_proposals WHERE id='00000000-0000-0000-0000-000000009601'", "append only");
            for (String table : List.of(
                "travel_guide_ai_proposal_actions",
                "travel_guide_ai_proposal_exclusions",
                "travel_guide_ai_proposal_violations"
            )) {
                schema.assertRejected("UPDATE " + table + " SET proposal_id='00000000-0000-0000-0000-000000009602'", "append only");
                schema.assertRejected("DELETE FROM " + table, "append only");
            }
            schema.assertRejected("UPDATE itinerary_items SET is_fixed=false WHERE id=9401", "identity is immutable");
            schema.assertRejected("UPDATE itinerary_items SET basket_item_id=9302 WHERE id=9401", "identity is immutable");
            schema.assertRejected("UPDATE trip_itinerary_states SET version=999 WHERE trip_id=9101", "history transition");
            schema.assertRejected("UPDATE trip_itinerary_states SET version=1 WHERE trip_id=9101", "history transition");
            schema.assertRejected("UPDATE trip_itinerary_states SET version=0 WHERE trip_id=9101", "history transition");
            schema.assertRejected("UPDATE trip_itinerary_states SET version=2 WHERE trip_id=9101", "history transition");
            schema.assertRejected("UPDATE trip_itinerary_states SET trip_id=9102 WHERE trip_id=9101", "identity is immutable");
            schema.assertRejected("DELETE FROM trip_itinerary_states WHERE trip_id=9101", "identity is immutable");
            assertThat(schema.version()).isEqualTo(1);
        }
    }

    @Test
    void v22UpgradeToV23AllowsOneCompleteApplyTransitionOnly() throws Exception {
        // Given
        try (DisposableSchema schema = DisposableSchema.create(dataSource)) {
            schema.migrate("22");
            schema.migrate(null);
            schema.readyFixture();

            // When
            schema.execute("""
                INSERT INTO itinerary_changes(id,trip_id,user_id,action,payload)
                VALUES (9501,9101,9001,'replace','{}')
                """);
            schema.execute("""
                UPDATE travel_guide_ai_proposals SET status='applied',
                    applied_client_id='00000000-0000-0000-0000-000000009701',
                    applied_payload_fingerprint=repeat('a',64), applied_change_id=9501,
                    applied_version=1, applied_at=CURRENT_TIMESTAMP
                WHERE id='00000000-0000-0000-0000-000000009601'
                """);

            // Then
            assertThat(schema.version()).isEqualTo(1);
            schema.assertRejected("UPDATE itinerary_changes SET payload='{}' WHERE id=9501", "append only");
            schema.assertRejected("DELETE FROM travel_guide_ai_proposal_actions WHERE proposal_id='00000000-0000-0000-0000-000000009601'", "append only");
            schema.assertRejected("UPDATE travel_guide_ai_proposals SET base_version=8 WHERE id='00000000-0000-0000-0000-000000009601'", "immutable");
            schema.assertRejected("UPDATE travel_guide_ai_proposals SET applied_version=2 WHERE id='00000000-0000-0000-0000-000000009601'", "single apply receipt");
            schema.assertRejected("UPDATE travel_guide_ai_proposals SET status='ready', applied_client_id=NULL, applied_payload_fingerprint=NULL, applied_change_id=NULL, applied_version=NULL, applied_at=NULL WHERE id='00000000-0000-0000-0000-000000009601'", "single apply receipt");
            schema.assertRejected("UPDATE trip_itinerary_states SET version=9 WHERE trip_id=9101", "history transition");
            assertThat(schema.migrationCount()).isEqualTo(31);
        }
    }

    private static final class DisposableSchema implements AutoCloseable {
        private final DataSource baseDataSource;
        private final DataSource scopedDataSource;
        private final String name;

        private DisposableSchema(DataSource baseDataSource, String name) {
            this.baseDataSource = baseDataSource;
            this.scopedDataSource = new SchemaScopedDataSource(baseDataSource, name);
            this.name = name;
        }

        static DisposableSchema create(DataSource dataSource) throws SQLException {
            String name = "task14_v23_" + UUID.randomUUID().toString().replace("-", "");
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA " + name);
            }
            return new DisposableSchema(dataSource, name);
        }

        void migrate(String target) {
            var configuration = Flyway.configure()
                .dataSource(scopedDataSource)
                .schemas(name)
                .defaultSchema(name)
                .locations("classpath:db/migration");
            if (target != null) {
                configuration.target(MigrationVersion.fromVersion(target));
            }
            configuration.load().migrate();
        }

        void readyFixture() throws SQLException {
            execute("INSERT INTO users(id,nickname) VALUES (9001,'owner'),(9002,'other')");
            execute("""
                INSERT INTO trips(id,owner_id,title,activity_type) VALUES
                (9101,9001,'trip','tour'),(9102,9002,'other','tour')
                """);
            execute("INSERT INTO trip_itinerary_states(trip_id,version) VALUES (9101,0),(9102,0) ON CONFLICT DO NOTHING");
            execute("""
                INSERT INTO places(id,name,category,lat,lng,source,normalized_name,compact_name)
                VALUES (9201,'fixed','CAFE',35.8,127.1,'user','fixed','fixed'),
                       (9202,'next','CAFE',35.81,127.11,'user','next','next')
                """);
            execute("""
                INSERT INTO basket_items(id,trip_id,added_by,item_type,place_id,source,title,lat,lng,status,client_item_id,payload_fingerprint)
                VALUES (9301,9101,9001,'place',9201,'user','fixed',35.8,127.1,'resolved','fixed',repeat('1',64)),
                       (9302,9101,9001,'place',9202,'user','next',35.81,127.11,'resolved','next',repeat('2',64))
                """);
            execute("INSERT INTO itinerary_items(id,trip_id,basket_item_id,day_number,order_index,planned_arrival,planned_duration_min,is_fixed) VALUES (9401,9101,9301,1,0,'09:00',60,true)");
            execute("""
                INSERT INTO travel_guide_ai_proposals(id,trip_id,created_by,base_version,proposal_fingerprint,feasible,status)
                VALUES ('00000000-0000-0000-0000-000000009601',9101,9001,0,repeat('a',64),true,'ready'),
                       ('00000000-0000-0000-0000-000000009602',9102,9002,0,repeat('b',64),false,'invalid')
                """);
            execute("INSERT INTO travel_guide_ai_proposal_actions VALUES ('00000000-0000-0000-0000-000000009601',0,9301,1,0,'09:00',60,0,true)");
            execute("INSERT INTO travel_guide_ai_proposal_exclusions VALUES ('00000000-0000-0000-0000-000000009601',0,9399)");
            execute("INSERT INTO travel_guide_ai_proposal_violations VALUES ('00000000-0000-0000-0000-000000009601',0,'sample',NULL)");
        }

        void fixture() throws SQLException {
            readyFixture();
            execute("INSERT INTO itinerary_changes(id,trip_id,user_id,action,payload) VALUES (9501,9101,9001,'replace','{}')");
            execute("""
                UPDATE travel_guide_ai_proposals SET status='applied',
                    applied_client_id='00000000-0000-0000-0000-000000009701',
                    applied_payload_fingerprint=repeat('a',64), applied_change_id=9501,
                    applied_version=1, applied_at=CURRENT_TIMESTAMP
                WHERE id='00000000-0000-0000-0000-000000009601'
                """);
        }

        void execute(String sql) throws SQLException {
            try (Connection connection = scopedDataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }

        void assertRejected(String sql, String message) {
            assertThatThrownBy(() -> execute(sql))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(message);
        }

        long version() throws SQLException {
            return scalar("SELECT version FROM trip_itinerary_states WHERE trip_id=9101");
        }

        long migrationCount() throws SQLException {
            return scalar("SELECT COUNT(*) FROM flyway_schema_history WHERE success");
        }

        long scalar(String sql) throws SQLException {
            try (Connection connection = scopedDataSource.getConnection(); Statement statement = connection.createStatement()) {
                try (var rows = statement.executeQuery(sql)) {
                    rows.next();
                    return rows.getLong(1);
                }
            }
        }

        @Override
        public void close() throws SQLException {
            try (Connection connection = baseDataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA " + name + " CASCADE");
            }
        }
    }
}
