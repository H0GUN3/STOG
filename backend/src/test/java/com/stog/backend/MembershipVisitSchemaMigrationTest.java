package com.stog.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stog.backend.cell.CellIdCalculator;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Wrapper;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

class MembershipVisitSchemaMigrationTest {
    @Test
    void freshSchemaContainsMembershipConstraintsVisitsAndTimeIntegrity() throws Exception {
        try (MigrationDatabase database = MigrationDatabase.open()) {
            database.resetToEmptySchema();
            database.migrateTo(null);

            assertThat(database.tables()).contains(
                "trip_member_collection_states",
                "trip_members",
                "user_constraints",
                "visits"
            );
            assertThat(database.scalarLong(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '19' AND success"
            )).isEqualTo(1);
            assertThat(database.columns("trip_members")).containsExactly(
                "joined_at", "left_at", "trip_id", "user_id"
            );
            assertThat(database.columns("trip_member_collection_states")).containsExactly(
                "collector_started_at",
                "collector_state",
                "mode_version",
                "permission_state",
                "sync_cursor",
                "trip_id",
                "updated_at",
                "user_id"
            );
            assertThat(database.columns("visits")).contains(
                "client_visit_id",
                "created_at",
                "payload_fingerprint"
            );
            assertThat(database.row(
                """
                SELECT data_type
                FROM information_schema.columns
        WHERE table_schema = current_schema()
                  AND table_name = 'itinerary_items'
                  AND column_name = 'planned_arrival'
                """
            )).isEqualTo("time without time zone");
        }
    }

    @Test
    void v10UpgradePreservesHistoryAndSupportsOwnerMemberConstraintVisitAndTimePaths() throws Exception {
        try (MigrationDatabase database = MigrationDatabase.open()) {
            database.resetToEmptySchema();
            database.migrateTo("10");

            long ownerId = database.user("owner", "한 지역 도보");
            long memberId = database.user("member", null);
            long tripId = database.trip(ownerId, "tour");
            long basketItemId = database.insertAndReturnId(
                """
                INSERT INTO basket_items (trip_id, added_by, item_type, original_url)
                VALUES (%d, %d, 'link', 'https://example.test/place')
                RETURNING id
                """.formatted(tripId, ownerId)
            );
            long itineraryItemId = database.insertAndReturnId(
                """
                INSERT INTO itinerary_items (
                    trip_id, basket_item_id, day_number, order_index, planned_arrival
                )
                VALUES (%d, %d, 1, 0, '09:30')
                RETURNING id
                """.formatted(tripId, basketItemId)
            );
            long changeId = database.insertAndReturnId(
                """
                INSERT INTO itinerary_changes (trip_id, user_id, action, payload)
                VALUES (%d, %d, 'replace', '{}'::jsonb)
                RETURNING id
                """.formatted(tripId, ownerId)
            );

            database.migrateTo(null);

            long cellId = CellIdCalculator.fromCoords(35.815, 127.15);
            database.execute(
                """
                INSERT INTO trip_members (trip_id, user_id)
                VALUES (%d, %d)
                """.formatted(tripId, memberId)
            );
            database.execute(
                """
                INSERT INTO visits (
                    user_id, trip_id, client_visit_id, payload_fingerprint,
                    cell_id, lat, lng, entered_at, left_at, status, is_interpolated
                )
                VALUES (
                    %d, %d, gen_random_uuid(), repeat('a', 64), %d, 35.815, 127.15,
                    '2026-08-20T09:00:00Z', '2026-08-20T09:30:00Z', 'visited', FALSE
                )
                """.formatted(memberId, tripId, cellId)
            );

            assertThat(database.row(
                "SELECT id || '|' || planned_arrival::text FROM itinerary_items WHERE id = %d"
                    .formatted(itineraryItemId)
            )).isEqualTo(itineraryItemId + "|09:30:00");
            assertThat(database.row(
                "SELECT id || '|' || action FROM itinerary_changes WHERE id = %d".formatted(changeId)
            )).isEqualTo(changeId + "|replace");
            assertThat(database.row(
                """
                SELECT constraint_type || '|' || constraint_code
                FROM user_constraints
                WHERE user_id = %d
                """.formatted(ownerId)
            )).isEqualTo("mobility_style|한 지역 도보");
            assertThat(database.row(
                """
                SELECT user_id || '|' || trip_id || '|' || cell_id || '|' || status || '|' || is_interpolated
                FROM visits
                WHERE trip_id = %d
                """.formatted(tripId)
            )).isEqualTo(memberId + "|" + tripId + "|" + cellId + "|visited|false");
        }
    }

    @Test
    void integrityConstraintsRejectInvalidWritesAndKeepInterpolatedPassesDistinct() throws Exception {
        try (MigrationDatabase database = MigrationDatabase.open()) {
            database.resetToEmptySchema();
            database.migrateTo(null);

            long ownerId = database.user("constraint-owner", null);
            long memberId = database.user("constraint-member", null);
            long tripId = database.trip(ownerId, "tour");
            long cellId = CellIdCalculator.fromCoords(35.815, 127.15);

            database.execute("INSERT INTO trip_members (trip_id, user_id) VALUES (%d, %d)".formatted(tripId, memberId));
            database.execute(
                """
                INSERT INTO visits (
                    user_id, trip_id, client_visit_id, payload_fingerprint,
                    cell_id, lat, lng, entered_at, left_at, status, is_interpolated
                )
                VALUES (
                    %d, %d, gen_random_uuid(), repeat('b', 64), %d, 35.815, 127.15,
                    '2026-08-20T09:00:00Z', '2026-08-20T09:05:00Z', 'passed', TRUE
                )
                """.formatted(memberId, tripId, cellId)
            );

            assertThat(database.row(
                "SELECT status || '|' || is_interpolated FROM visits WHERE trip_id = %d".formatted(tripId)
            )).isEqualTo("passed|true");
            assertRejected(() -> database.execute(
                "INSERT INTO trip_members (trip_id, user_id) VALUES (%d, %d)".formatted(tripId, memberId)
            ));
            assertRejected(() -> database.execute(
                "INSERT INTO trip_members (trip_id, user_id, role) VALUES (%d, %d, 'owner')"
                    .formatted(tripId, ownerId)
            ));
            assertRejected(() -> database.execute(
                "INSERT INTO trips (owner_id, title, activity_type) VALUES (%d, 'invalid', 'hike')"
                    .formatted(ownerId)
            ));
            assertRejected(() -> database.execute(
                """
                INSERT INTO user_constraints (user_id, constraint_type, constraint_code)
                VALUES (%d, 'mobility_style', 'flying')
                """.formatted(ownerId)
            ));
            assertRejected(() -> database.execute(
                """
                INSERT INTO user_constraints (user_id, constraint_type, constraint_code)
                VALUES (999999, 'mobility_style', '한 지역 도보')
                """
            ));
            assertRejected(() -> database.execute(
                """
                INSERT INTO visits (
                    user_id, trip_id, cell_id, lat, lng, entered_at, left_at, status, is_interpolated
                )
                VALUES (
                    %d, %d, %d, 35.815, 127.15,
                    '2026-08-20T09:00:00Z', '2026-08-20T09:30:00Z', 'unknown', FALSE
                )
                """.formatted(memberId, tripId, cellId)
            ));
            assertRejected(() -> database.execute(
                """
                INSERT INTO visits (
                    user_id, trip_id, cell_id, lat, lng, entered_at, left_at, status, is_interpolated
                )
                VALUES (
                    %d, %d, 0, 35.815, 127.15,
                    '2026-08-20T09:00:00Z', '2026-08-20T09:30:00Z', 'visited', FALSE
                )
                """.formatted(memberId, tripId)
            ));
            assertRejected(() -> database.execute(
                """
                INSERT INTO visits (
                    user_id, trip_id, cell_id, lat, lng, entered_at, left_at, status, is_interpolated
                )
                VALUES (
                    %d, %d, %d, 35.815, 127.15,
                    '2026-08-20T09:00:00Z', '2026-08-20T09:30:00Z', 'visited', TRUE
                )
                """.formatted(memberId, tripId, cellId)
            ));
            assertRejected(() -> database.execute(
                """
                INSERT INTO itinerary_changes (trip_id, user_id, action, payload)
                VALUES (%d, %d, 'rewrite', '{}'::jsonb)
                """.formatted(tripId, ownerId)
            ));
        }
    }

    @Test
    void latestVisitIdentityIsMemberScopedUniqueAndImmutable() throws Exception {
        try (MigrationDatabase database = MigrationDatabase.open()) {
            database.resetToEmptySchema();
            database.migrateTo(null);

            long ownerId = database.user("identity-owner", null);
            long memberId = database.user("identity-member", null);
            long tripId = database.trip(ownerId, "tour");
            long cellId = CellIdCalculator.fromCoords(35.815, 127.15);
            database.execute(
                "INSERT INTO trip_members (trip_id, user_id) VALUES (%d, %d)"
                    .formatted(tripId, memberId)
            );
            String clientVisitId = "11111111-1111-1111-1111-111111111111";
            long ownerVisitId = database.insertAndReturnId(
                """
                INSERT INTO visits (
                    user_id, trip_id, client_visit_id, payload_fingerprint,
                    cell_id, lat, lng, entered_at, left_at, status
                )
                VALUES (
                    %d, %d, '%s', repeat('a', 64), %d, 35.815, 127.15,
                    '2026-08-20T09:00:00Z', '2026-08-20T09:30:00Z', 'visited'
                )
                RETURNING id
                """.formatted(ownerId, tripId, clientVisitId, cellId)
            );
            database.execute(
                """
                INSERT INTO visits (
                    user_id, trip_id, client_visit_id, payload_fingerprint,
                    cell_id, lat, lng, entered_at, left_at, status
                )
                VALUES (
                    %d, %d, '%s', repeat('a', 64), %d, 35.815, 127.15,
                    '2026-08-20T09:00:00Z', '2026-08-20T09:30:00Z', 'visited'
                )
                """.formatted(memberId, tripId, clientVisitId, cellId)
            );

            assertRejected(() -> database.execute(
                """
                INSERT INTO visits (
                    user_id, trip_id, client_visit_id, payload_fingerprint,
                    cell_id, lat, lng, entered_at, left_at, status
                )
                VALUES (
                    %d, %d, '%s', repeat('b', 64), %d, 35.815, 127.15,
                    '2026-08-20T09:00:00Z', '2026-08-20T09:31:00Z', 'visited'
                )
                """.formatted(ownerId, tripId, clientVisitId, cellId)
            ));
            assertRejected(() -> database.execute(
                "UPDATE visits SET payload_fingerprint = repeat('c', 64) WHERE id = %d"
                    .formatted(ownerVisitId)
            ));
            assertThat(database.scalarLong(
                "SELECT COUNT(*) FROM visits WHERE trip_id = %d".formatted(tripId)
            )).isEqualTo(2);
        }
    }

    @Test
    void v17UpgradeBackfillsOwnerMembershipAndImmutableVisitIdentity() throws Exception {
        try (MigrationDatabase database = MigrationDatabase.open()) {
            database.resetToEmptySchema();
            database.migrateTo("17");

            long ownerId = database.user("recording-owner", null);
            long tripId = database.trip(ownerId, "tour");
            long visitId = database.insertAndReturnId(
                """
                INSERT INTO visits (
                    user_id, trip_id, cell_id, lat, lng, entered_at, left_at, status
                )
                VALUES (
                    %d, %d, %d, 35.815, 127.15,
                    '2026-08-20T09:00:00Z', '2026-08-20T09:30:00Z', 'visited'
                )
                RETURNING id
                """.formatted(
                    ownerId,
                    tripId,
                    CellIdCalculator.fromCoords(35.815, 127.15)
                )
            );

            database.migrateTo(null);

            assertThat(database.row(
                """
                SELECT member.user_id || '|' || state.collector_state || '|' || state.mode_version
                FROM trip_members member
                JOIN trip_member_collection_states state
                  ON state.trip_id = member.trip_id AND state.user_id = member.user_id
                WHERE member.trip_id = %d AND member.user_id = %d
                """.formatted(tripId, ownerId)
            )).isEqualTo(ownerId + "|inactive|0");
            assertThat(database.row(
                """
                SELECT client_visit_id IS NOT NULL || '|' || length(payload_fingerprint) || '|' ||
                       (created_at IS NOT NULL)::text
                FROM visits
                WHERE id = %d
                """.formatted(visitId)
            )).isEqualTo("true|64|true");
        }
    }

    @Test
    void v10UpgradeRejectsNonConvertibleArrivalBeforeCreatingV11Objects() throws Exception {
        try (MigrationDatabase database = MigrationDatabase.open()) {
            database.resetToEmptySchema();
            database.migrateTo("10");

            long ownerId = database.user("invalid-time-owner", null);
            long tripId = database.trip(ownerId, "tour");
            long basketItemId = database.insertAndReturnId(
                """
                INSERT INTO basket_items (trip_id, added_by, item_type, original_url)
                VALUES (%d, %d, 'link', 'https://example.test/invalid-time')
                RETURNING id
                """.formatted(tripId, ownerId)
            );
            database.execute(
                """
                INSERT INTO itinerary_items (
                    trip_id, basket_item_id, day_number, order_index, planned_arrival
                )
                VALUES (%d, %d, 1, 0, 'not-a-time')
                """.formatted(tripId, basketItemId)
            );

            assertThatThrownBy(() -> database.migrateTo(null)).isInstanceOf(RuntimeException.class);
            assertThat(database.tableExists("trip_members")).isFalse();
            assertThat(database.tableExists("user_constraints")).isFalse();
            assertThat(database.tableExists("visits")).isFalse();
        }
    }

    private static void assertRejected(SqlWrite write) {
        assertThatThrownBy(write::run).isInstanceOf(SQLException.class);
    }

    @FunctionalInterface
    private interface SqlWrite {
        void run() throws SQLException;
    }

    private static final class MigrationDatabase implements AutoCloseable {
        private final Connection connection;
        private final String schema;

        private MigrationDatabase(Connection connection, String schema) {
            this.connection = connection;
            this.schema = schema;
        }

        static MigrationDatabase open() throws SQLException, IOException {
            Connection connection = DriverManager.getConnection(
                TestDatabaseProperties.URL,
                TestDatabaseProperties.USERNAME,
                TestDatabaseProperties.password()
            );
            String schema = "stog_test_migration_"
                + UUID.randomUUID().toString().replace("-", "");
            connection.setAutoCommit(true);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA " + quoteIdentifier(schema));
            }
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + quoteIdentifier(schema) + ", public");
            }
            return new MigrationDatabase(connection, schema);
        }

        void resetToEmptySchema() {
        }

        void migrateTo(String targetVersion) {
            var configuration = Flyway.configure()
                .dataSource(new SingleConnectionDataSource(connection))
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("0"))
                .defaultSchema(schema)
                .schemas(schema)
                .initSql("SET search_path TO " + quoteIdentifier(schema) + ", public")
                .locations("classpath:db/migration");
            if (targetVersion != null) {
                configuration.target(MigrationVersion.fromVersion(targetVersion));
            }
            try {
                configuration.load().migrate();
            } finally {
                restoreSearchPath();
            }
        }

        private static String quoteIdentifier(String identifier) {
            return "\"" + identifier.replace("\"", "\"\"") + "\"";
        }

        private void restoreSearchPath() {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + quoteIdentifier(schema) + ", public");
            } catch (SQLException exception) {
                throw new IllegalStateException("Unable to restore migration search path", exception);
            }
        }

        long user(String nickname, String mobilityStyle) throws SQLException {
            String mobilityStyleSql = mobilityStyle == null ? "NULL" : "'%s'".formatted(mobilityStyle);
            return insertAndReturnId(
                """
                INSERT INTO users (nickname, mobility_style)
                VALUES ('%s', %s)
                RETURNING id
                """.formatted(nickname, mobilityStyleSql)
            );
        }

        long trip(long ownerId, String activityType) throws SQLException {
            return insertAndReturnId(
                """
                INSERT INTO trips (owner_id, title, activity_type)
                VALUES (%d, 'migration trip', '%s')
                RETURNING id
                """.formatted(ownerId, activityType)
            );
        }

        long insertAndReturnId(String sql) throws SQLException {
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }

        void execute(String sql) throws SQLException {
            java.sql.Savepoint savepoint = connection.setSavepoint();
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
                connection.releaseSavepoint(savepoint);
            } catch (SQLException exception) {
                connection.rollback(savepoint);
                throw exception;
            }
        }

        long scalarLong(String sql) throws SQLException {
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }

        String row(String sql) throws SQLException {
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }

        List<String> columns(String tableName) throws SQLException {
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = '%s'
                ORDER BY column_name
                """.formatted(tableName)
            )) {
                java.util.ArrayList<String> columns = new java.util.ArrayList<>();
                while (result.next()) {
                    columns.add(result.getString(1));
                }
                return columns;
            }
        }

        boolean tableExists(String tableName) throws SQLException {
            return scalarLong(
                """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = '%s' AND table_name = '%s'
                """.formatted(schema, tableName)
            ) == 1;
        }

        List<String> tables() throws SQLException {
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = current_schema()
                ORDER BY table_name
                """
            )) {
                java.util.ArrayList<String> tables = new java.util.ArrayList<>();
                while (result.next()) {
                    tables.add(result.getString(1));
                }
                return tables;
            }
        }

        @Override
        public void close() throws SQLException {
            try {
                connection.rollback();
            } finally {
                try {
                    connection.setAutoCommit(true);
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("DROP SCHEMA IF EXISTS "
                            + quoteIdentifier(schema) + " CASCADE");
                    }
                } finally {
                    connection.close();
                }
            }
        }

    }

    private static final class SingleConnectionDataSource implements DataSource {
        private final Connection connection;

        private SingleConnectionDataSource(Connection connection) {
            this.connection = connection;
        }

        @Override
        public Connection getConnection() {
            return closeShield(connection);
        }

        @Override
        public Connection getConnection(String username, String password) {
            return closeShield(connection);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) {
                return iface.cast(this);
            }
            throw new SQLException("Not a wrapper for " + iface.getName());
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return iface.isInstance(this);
        }

        private static Connection closeShield(Connection connection) {
            return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class, Wrapper.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("close") || method.getName().equals("commit")) {
                        return null;
                    }
                    if (method.getName().equals("setAutoCommit")
                        && arguments != null
                        && Boolean.TRUE.equals(arguments[0])) {
                        return null;
                    }
                    try {
                        return method.invoke(connection, arguments);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                }
            );
        }
    }
}
