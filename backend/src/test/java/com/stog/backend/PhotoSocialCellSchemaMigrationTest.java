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

public class PhotoSocialCellSchemaMigrationTest {
    @Test
    void freshSchemaAddsSocialTablesPrivatePendingPhotosAndSparsePublicProjection() throws Exception {
        try (MigrationDatabase database = MigrationDatabase.open()) {
            database.resetToEmptySchema();
            database.migrateTo(null);

            assertThat(database.scalarLong(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '12' AND success"
            )).isEqualTo(1);
            assertThat(database.tables()).contains(
                "cell_stats",
                "likes",
                "photo_moderation_events",
                "photo_public_grants"
            );
            assertThat(database.tableExists("cells")).isFalse();
            assertThat(database.columns("cell_stats")).containsExactly(
                "cell_id",
                "landmark_count",
                "public_photo_count",
                "public_photo_like_count",
                "top_photo_id",
                "updated_at"
            );
            assertThat(database.indexNames()).contains(
                "cell_stats_public_photo_count_idx",
                "cell_stats_public_photo_like_count_idx",
                "photo_public_grants_active_photo_unique",
                "photos_public_eligibility_cell_rank_idx",
                "places_public_catalog_cell_idx"
            );
            assertThat(database.scalarLong("SELECT COUNT(*) FROM cell_stats")).isZero();

            long ownerId = database.user("fresh-owner");
            long tripId = database.trip(ownerId, "public");
            long photoId = database.newPhoto(tripId, ownerId);

            assertThat(database.row(
                "SELECT visibility || '|' || moderation_status FROM photos WHERE id = %d".formatted(photoId)
            )).isEqualTo("private|pending");
            assertThat(database.row(
                "SELECT is_moderator::text FROM users WHERE id = %d".formatted(ownerId)
            )).isEqualTo("false");
            assertRejected(() -> database.execute(
                "INSERT INTO cell_stats (cell_id) VALUES (%d)".formatted(
                    CellIdCalculator.fromCoords(35.815, 127.15)
                )
            ));
        }
    }

    @Test
    void v11UpgradePreservesPhotoObjectKeysAndBackfillsPendingModeration() throws Exception {
        try (MigrationDatabase database = MigrationDatabase.open()) {
            database.resetToEmptySchema();
            database.migrateTo("11");

            long ownerId = database.user("upgrade-owner");
            long tripId = database.trip(ownerId, "public");
            long photoId = database.newPhoto(tripId, ownerId);

            database.migrateTo(null);

            assertThat(database.scalarLong(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '12' AND success"
            )).isEqualTo(1);
            assertThat(database.row(
                """
                SELECT original_key || '|' || thumb_key || '|' || visibility || '|' || moderation_status
                FROM photos
                WHERE id = %d
                """.formatted(photoId)
            )).isEqualTo("photos/1/original.jpg|photos/1/thumb.jpg|private|pending");
            assertThat(database.row(
                "SELECT is_moderator::text FROM users WHERE id = %d".formatted(ownerId)
            )).isEqualTo("false");
        }
    }

    @Test
    void privacyGrantLikeAndTopPhotoConstraintsExcludeIneligiblePhotos() throws Exception {
        try (MigrationDatabase database = MigrationDatabase.open()) {
            database.resetToEmptySchema();
            database.migrateTo(null);

            long ownerId = database.user("social-owner");
            long likerId = database.user("social-liker");
            long moderatorId = database.user("social-moderator");
            database.execute("UPDATE users SET is_moderator = TRUE WHERE id = %d".formatted(moderatorId));

            long publicTripId = database.trip(ownerId, "public");
            long privateTripId = database.trip(ownerId, "private");
            long cellId = CellIdCalculator.fromCoords(35.815, 127.15);
            long otherCellId = CellIdCalculator.fromCoords(35.920, 127.15);
            assertThat(otherCellId).isNotEqualTo(cellId);

            long publicOnlyPhotoId = database.photo(
                publicTripId, ownerId, "public", "approved", cellId, 35.815, 127.15
            );
            long pendingPhotoId = database.photo(
                publicTripId, ownerId, "public", "pending", cellId, 35.815, 127.15
            );
            database.grant(pendingPhotoId, ownerId, 1);
            long blockedPhotoId = database.photo(
                publicTripId, ownerId, "public", "blocked", cellId, 35.815, 127.15
            );
            database.grant(blockedPhotoId, ownerId, 1);
            long revokedGrantPhotoId = database.photo(
                publicTripId, ownerId, "public", "approved", cellId, 35.815, 127.15
            );
            long revokedGrantId = database.grant(revokedGrantPhotoId, ownerId, 1);
            database.execute(
                "UPDATE photo_public_grants SET revoked_at = CURRENT_TIMESTAMP WHERE id = %d"
                    .formatted(revokedGrantId)
            );
            long privateTripPhotoId = database.photo(
                privateTripId, ownerId, "public", "approved", cellId, 35.815, 127.15
            );
            database.grant(privateTripPhotoId, ownerId, 1);
            long coordinateLessPhotoId = database.photo(
                publicTripId, ownerId, "public", "approved", null, null, null
            );
            database.grant(coordinateLessPhotoId, ownerId, 1);
            long eligiblePhotoId = database.photo(
                publicTripId, ownerId, "public", "approved", cellId, 35.815, 127.15
            );
            database.grant(eligiblePhotoId, ownerId, 1);
            assertRejected(() -> database.photo(
                publicTripId, ownerId, "public", "approved", cellId, null, null
            ));

            assertThat(database.isPubliclyEligible(publicOnlyPhotoId)).isFalse();
            assertThat(database.isPubliclyEligible(pendingPhotoId)).isFalse();
            assertThat(database.isPubliclyEligible(blockedPhotoId)).isFalse();
            assertThat(database.isPubliclyEligible(revokedGrantPhotoId)).isFalse();
            assertThat(database.isPubliclyEligible(privateTripPhotoId)).isFalse();
            assertThat(database.isPubliclyEligible(coordinateLessPhotoId)).isTrue();
            assertThat(database.isCellEligible(coordinateLessPhotoId)).isFalse();
            assertThat(database.isCellEligible(eligiblePhotoId)).isTrue();

            long versionedGrantPhotoId = database.photo(
                publicTripId, ownerId, "public", "approved", cellId, 35.815, 127.15
            );
            long firstGrantId = database.grant(versionedGrantPhotoId, ownerId, 1);
            assertRejected(() -> database.grant(versionedGrantPhotoId, ownerId, 2));
            database.execute(
                "UPDATE photo_public_grants SET revoked_at = CURRENT_TIMESTAMP WHERE id = %d"
                    .formatted(firstGrantId)
            );
            database.grant(versionedGrantPhotoId, ownerId, 2);
            assertThat(database.scalarLong(
                """
                SELECT COUNT(*)
                FROM photo_public_grants
                WHERE photo_id = %d AND revoked_at IS NULL
                """.formatted(versionedGrantPhotoId)
            )).isEqualTo(1);

            database.execute(
                "INSERT INTO likes (user_id, target_type, target_id) VALUES (%d, 'photo', %d)"
                    .formatted(likerId, eligiblePhotoId)
            );
            assertRejected(() -> database.execute(
                "INSERT INTO likes (user_id, target_type, target_id) VALUES (%d, 'photo', %d)"
                    .formatted(likerId, eligiblePhotoId)
            ));
            assertRejected(() -> database.execute(
                "INSERT INTO likes (user_id, target_type, target_id) VALUES (%d, 'place', %d)"
                    .formatted(likerId, eligiblePhotoId)
            ));

            long moderationEventPhotoId = database.newPhoto(publicTripId, ownerId);
            long moderationEventId = database.insertAndReturnId(
                """
                INSERT INTO photo_moderation_events (
                    photo_id, moderator_id, from_status, to_status, reason
                )
                VALUES (%d, %d, 'pending', 'approved', 'approved for fixture')
                RETURNING id
                """.formatted(moderationEventPhotoId, moderatorId)
            );
            assertRejected(() -> database.execute(
                "UPDATE photo_moderation_events SET reason = 'rewritten' WHERE id = %d"
                    .formatted(moderationEventId)
            ));
            assertRejected(() -> database.execute(
                "DELETE FROM photo_moderation_events WHERE id = %d".formatted(moderationEventId)
            ));
            assertRejected(() -> database.execute(
                """
                INSERT INTO photo_moderation_events (photo_id, moderator_id, from_status, to_status)
                VALUES (%d, %d, 'approved', 'blocked')
                """.formatted(publicOnlyPhotoId, moderatorId)
            ));

            database.execute(
                """
                INSERT INTO cell_stats (
                    cell_id, landmark_count, public_photo_count, public_photo_like_count, top_photo_id
                )
                VALUES (%d, 0, 1, 1, %d)
                """.formatted(cellId, eligiblePhotoId)
            );
            assertThat(database.row(
                "SELECT cell_id || '|' || top_photo_id FROM cell_stats WHERE cell_id = %d".formatted(cellId)
            )).isEqualTo(cellId + "|" + eligiblePhotoId);
            assertRejected(() -> database.execute(
                """
                INSERT INTO cell_stats (
                    cell_id, landmark_count, public_photo_count, public_photo_like_count, top_photo_id
                )
                VALUES (%d, 1, 0, 0, %d)
                """.formatted(otherCellId, eligiblePhotoId)
            ));
            assertRejected(() -> database.execute(
                """
                INSERT INTO cell_stats (
                    cell_id, landmark_count, public_photo_count, public_photo_like_count, top_photo_id
                )
                VALUES (%d, 0, 1, 0, %d)
                """.formatted(cellId, coordinateLessPhotoId)
            ));
            assertThat(database.scalarLong(
                """
                SELECT COUNT(*)
                FROM cell_stats
                WHERE top_photo_id IN (%d, %d, %d, %d, %d, %d)
                """.formatted(
                    publicOnlyPhotoId,
                    pendingPhotoId,
                    blockedPhotoId,
                    revokedGrantPhotoId,
                    privateTripPhotoId,
                    coordinateLessPhotoId
                )
            )).isZero();
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
        private long photoSequence;

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

        long user(String nickname) throws SQLException {
            return insertAndReturnId(
                "INSERT INTO users (nickname) VALUES ('%s') RETURNING id".formatted(nickname)
            );
        }

        long trip(long ownerId, String visibility) throws SQLException {
            return insertAndReturnId(
                """
                INSERT INTO trips (owner_id, title, activity_type, visibility)
                VALUES (%d, 'social migration trip', 'tour', '%s')
                RETURNING id
                """.formatted(ownerId, visibility)
            );
        }

        long newPhoto(long tripId, long ownerId) throws SQLException {
            long sequence = ++photoSequence;
            return insertAndReturnId(
                """
                INSERT INTO photos (trip_id, user_id, source, original_key, thumb_key)
                VALUES (%d, %d, 'camera', 'photos/%d/original.jpg', 'photos/%d/thumb.jpg')
                RETURNING id
                """.formatted(tripId, ownerId, sequence, sequence)
            );
        }

        long photo(
            long tripId,
            long ownerId,
            String visibility,
            String moderationStatus,
            Long cellId,
            Double latitude,
            Double longitude
        ) throws SQLException {
            long sequence = ++photoSequence;
            String cellIdSql = cellId == null ? "NULL" : cellId.toString();
            String latitudeSql = latitude == null ? "NULL" : latitude.toString();
            String longitudeSql = longitude == null ? "NULL" : longitude.toString();
            return insertAndReturnId(
                """
                INSERT INTO photos (
                    trip_id, user_id, source, cell_id, lat, lng, original_key, thumb_key,
                    visibility, moderation_status
                )
                VALUES (
                    %d, %d, 'camera', %s, %s, %s, 'photos/%d/original.jpg',
                    'photos/%d/thumb.jpg', '%s', '%s'
                )
                RETURNING id
                """.formatted(
                    tripId,
                    ownerId,
                    cellIdSql,
                    latitudeSql,
                    longitudeSql,
                    sequence,
                    sequence,
                    visibility,
                    moderationStatus
                )
            );
        }

        long grant(long photoId, long grantedBy, int version) throws SQLException {
            return insertAndReturnId(
                """
                INSERT INTO photo_public_grants (photo_id, version, granted_by)
                VALUES (%d, %d, %d)
                RETURNING id
                """.formatted(photoId, version, grantedBy)
            );
        }

        boolean isPubliclyEligible(long photoId) throws SQLException {
            return scalarLong(
                """
                SELECT COUNT(*)
                FROM photos photo
                JOIN trips trip ON trip.id = photo.trip_id
                JOIN photo_public_grants public_grant ON public_grant.photo_id = photo.id
                WHERE photo.id = %d
                  AND trip.visibility = 'public'
                  AND photo.visibility = 'public'
                  AND photo.moderation_status = 'approved'
                  AND public_grant.revoked_at IS NULL
                """.formatted(photoId)
            ) == 1;
        }

        boolean isCellEligible(long photoId) throws SQLException {
            return scalarLong(
                """
                SELECT COUNT(*)
                FROM photos photo
                JOIN trips trip ON trip.id = photo.trip_id
                JOIN photo_public_grants public_grant ON public_grant.photo_id = photo.id
                WHERE photo.id = %d
                  AND trip.visibility = 'public'
                  AND photo.visibility = 'public'
                  AND photo.moderation_status = 'approved'
                  AND public_grant.revoked_at IS NULL
                  AND photo.cell_id IS NOT NULL
                  AND photo.lat IS NOT NULL
                  AND photo.lng IS NOT NULL
                """.formatted(photoId)
            ) == 1;
        }

        long insertAndReturnId(String sql) throws SQLException {
            java.sql.Savepoint savepoint = connection.setSavepoint();
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
                assertThat(result.next()).isTrue();
                long id = result.getLong(1);
                connection.releaseSavepoint(savepoint);
                return id;
            } catch (SQLException exception) {
                connection.rollback(savepoint);
                throw exception;
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

        List<String> indexNames() throws SQLException {
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
                """
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = current_schema()
                ORDER BY indexname
                """
            )) {
                java.util.ArrayList<String> indexNames = new java.util.ArrayList<>();
                while (result.next()) {
                    indexNames.add(result.getString(1));
                }
                return indexNames;
            }
        }

        boolean tableExists(String tableName) throws SQLException {
            return scalarLong(
                """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = current_schema() AND table_name = '%s'
                """.formatted(tableName)
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
