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

public class CatalogSchemaMigrationTest {
    @Test
    void freshSchemaContainsCatalogProvenanceLexicalSearchAndEvents() throws Exception {
        try (MigrationDatabase database = MigrationDatabase.open()) {
            database.resetToEmptySchema();
            database.migrateTo(null);

            assertThat(database.catalogFunctionCount()).isEqualTo(10);
            assertThat(database.tables()).contains(
                "catalog_sources",
                "license_snapshots",
                "place_aliases",
                "place_details",
                "place_source_images",
                "place_source_records",
                "places",
                "events"
            );
            assertThat(database.scalarLong(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '33' AND success"
            )).isEqualTo(1);
            assertThat(database.scalarLong(
                """
                SELECT COUNT(*)
                FROM pg_indexes
                WHERE schemaname = current_schema()
                  AND indexname IN (
                    'places_normalized_name_trgm_idx',
                    'places_compact_name_trgm_idx',
                    'place_aliases_normalized_name_trgm_idx',
                    'place_aliases_compact_name_trgm_idx'
                  )
                """
            )).isEqualTo(4);
            assertThat(database.scalarLong(
                """
                SELECT COUNT(*)
                FROM pg_indexes
                WHERE schemaname = current_schema()
                  AND indexname = 'events_nearby_date_idx'
                """
            )).isEqualTo(1);
            assertThat(database.scalarLong(
                """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = current_schema()
                  AND table_name = 'cells'
                """
            )).isZero();
            assertThat(database.row(
                """
                SELECT is_nullable
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'place_source_images'
                  AND column_name = 'object_key'
                """
            )).isEqualTo("YES");
        }
    }

    @Test
    void v9UpgradeBackfillsExistingPlacesAsPrivateReferences() throws Exception {
        try (MigrationDatabase database = MigrationDatabase.open()) {
            database.resetToEmptySchema();
            database.migrateTo("9");
            long placeId = database.insertAndReturnId(
                """
                INSERT INTO places (name, category, lat, lng, cell_id, source)
                VALUES ('Jeonju--Hanok Village', 'tourist', 35.815, 127.15, 0, 'public_data')
                RETURNING id
                """
            );

            database.migrateTo(null);

            assertThat(database.row(
                """
                SELECT catalog_status || '|' || normalized_name || '|' || compact_name
                FROM places
                WHERE id = %d
                """.formatted(placeId)
            )).isEqualTo("private_reference|jeonju hanok village|jeonjuhanokvillage");
        }
    }

    @Test
    void latestSchemaAllowsUnknownSourceUpdatedAt() throws Exception {
        try (MigrationDatabase database = MigrationDatabase.open()) {
            database.resetToEmptySchema();
            database.migrateTo(null);

            assertThat(database.row(
                """
                SELECT is_nullable
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'place_source_records'
                  AND column_name = 'source_updated_at'
                """
            )).isEqualTo("YES");
        }
    }

    @Test
    void latestSchemaStoresPublicCellEligibility() throws Exception {
        try (MigrationDatabase database = MigrationDatabase.open()) {
            database.resetToEmptySchema();
            database.migrateTo(null);

            assertThat(database.row(
                """
                SELECT column_name || '|' || column_default
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'places'
                  AND column_name = 'public_cell_eligible'
                """
            )).contains("public_cell_eligible|false");
        }
    }

    @Test
    void catalogConstraintsRequireLicensedProvenanceAndValidatedDetails() throws Exception {
        try (MigrationDatabase database = MigrationDatabase.open()) {
            database.resetToEmptySchema();
            database.migrateTo(null);

            long placeId = database.insertAndReturnId(
                """
                INSERT INTO places (
                    name, category, lat, lng, cell_id, source, normalized_name, compact_name
                )
                VALUES (
                    'Jeonju Hanok Village', 'tourist', 35.815, 127.15, %d, 'public_data',
                    'jeonju hanok village', 'jeonjuhanokvillage'
                )
                RETURNING id
                """.formatted(CellIdCalculator.fromCoords(35.815, 127.15))
            );
            long sourceId = database.insertAndReturnId(
                """
                INSERT INTO catalog_sources (source_key, name, provider_type, active)
                VALUES ('jeonbuk-tourism', 'Jeonbuk Tourism', 'public_data', TRUE)
                RETURNING id
                """
            );
            long licenseId = database.insertAndReturnId(
                """
                INSERT INTO license_snapshots (
                    catalog_source_id, license_name, reviewed_at, valid_from,
                    allows_public_discovery, reusable_fields, digest
                )
                VALUES (
                    %d, 'Open data license', CURRENT_TIMESTAMP, CURRENT_DATE - 1,
                    TRUE, '["name", "address", "image"]'::jsonb, 'license-digest-1'
                )
                RETURNING id
                """.formatted(sourceId)
            );
            long sourceRecordId = database.insertAndReturnId(
                """
                INSERT INTO place_source_records (
                    place_id, catalog_source_id, license_snapshot_id, external_id,
                    source_digest, source_updated_at, active, imported_at
                )
                VALUES (
                    %d, %d, %d, 'tour-1', 'source-digest-1', CURRENT_TIMESTAMP, TRUE,
                    CURRENT_TIMESTAMP
                )
                RETURNING id
                """.formatted(placeId, sourceId, licenseId)
            );

            database.execute(
                """
                INSERT INTO place_aliases (
                    place_id, alias, normalized_name, compact_name, source_record_id, alias_source
                )
                VALUES (%d, 'Hanok Village', 'hanok village', 'hanokvillage', %d, 'source')
                """.formatted(placeId, sourceRecordId)
            );
            database.execute(
                """
                INSERT INTO place_source_images (
                    place_source_record_id, source_image_id, license_snapshot_id,
                    source_digest, reusable
                )
                VALUES (%d, 'image-1', %d, 'image-digest-1', TRUE)
                """.formatted(sourceRecordId, licenseId)
            );
            database.execute(
                """
                INSERT INTO place_details (
                    place_id, address, contact, accessibility, opening_hours, date_overrides,
                    cross_midnight, estimated_cost, visit_minutes, visit_minutes_source,
                    visit_minutes_override, traits, traits_source, traits_model,
                    traits_updated_at, source_updated_at
                )
                VALUES (
                    %d, 'Jeonju-si', '{"phone":"063-000-0000"}'::jsonb,
                    '{"wheelchair":true}'::jsonb,
                    '[{"day":"monday","opens_at":"09:00","closes_at":"01:00"}]'::jsonb,
                    '[{"date":"2026-12-25","opens_at":"10:00","closes_at":"12:00"}]'::jsonb,
                    TRUE, '{"currency":"KRW","amount":0}'::jsonb, 60, 'source', 75,
                    '{"nature":true,"culture":true,"food":true}'::jsonb,
                    'source', 'traits-v1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """.formatted(placeId)
            );
            database.execute("UPDATE places SET catalog_status = 'public' WHERE id = %d".formatted(placeId));

            assertThat(database.row(
                "SELECT catalog_status FROM places WHERE id = %d".formatted(placeId)
            )).isEqualTo("public");
            assertThat(database.row(
                "SELECT normalized_name FROM place_aliases WHERE place_id = %d".formatted(placeId)
            )).isEqualTo("hanok village");

            long unlicensedPlaceId = database.insertAndReturnId(
                """
                INSERT INTO places (name, lat, lng, source, normalized_name, compact_name)
                VALUES ('Unlicensed Place', 35.8, 127.1, 'user', 'unlicensed place', 'unlicensedplace')
                RETURNING id
                """
            );
            assertRejected(() -> database.execute(
                "UPDATE places SET catalog_status = 'public' WHERE id = %d".formatted(unlicensedPlaceId)
            ));
            assertRejected(() -> database.execute(
                """
                INSERT INTO place_source_records (
                    place_id, catalog_source_id, license_snapshot_id, external_id,
                    source_digest, source_updated_at, active, imported_at
                )
                VALUES (%d, %d, %d, 'tour-1', 'source-digest-2', CURRENT_TIMESTAMP, TRUE, CURRENT_TIMESTAMP)
                """.formatted(placeId, sourceId, licenseId)
            ));
            assertRejected(() -> database.execute(
                """
                INSERT INTO place_source_records (
                    place_id, catalog_source_id, license_snapshot_id, external_id,
                    source_digest, source_updated_at, active, imported_at
                )
                VALUES (%d, %d, %d, 'tour-2', 'source-digest-1', CURRENT_TIMESTAMP, TRUE, CURRENT_TIMESTAMP)
                """.formatted(placeId, sourceId, licenseId)
            ));
            assertRejected(() -> database.execute(
                """
                INSERT INTO place_source_records (
                    place_id, catalog_source_id, license_snapshot_id, external_id,
                    source_digest, source_updated_at, active, imported_at
                )
                VALUES (%d, %d, %d, 'tour-3', 'source-digest-3', CURRENT_TIMESTAMP, TRUE, CURRENT_TIMESTAMP)
                """.formatted(placeId, sourceId, licenseId)
            ));
            assertRejected(() -> database.execute(
                """
                INSERT INTO places (name, lat, lng, source, normalized_name, compact_name)
                VALUES ('Broken coordinates', NULL, 127.2, 'user', 'broken coordinates', 'brokencoordinates')
                """
            ));
            assertRejected(() -> database.execute(
                "UPDATE places SET catalog_status = 'unknown' WHERE id = %d".formatted(placeId)
            ));
            assertRejected(() -> database.execute(
                "UPDATE place_source_records SET active = FALSE WHERE id = %d".formatted(sourceRecordId)
            ));
            assertRejected(() -> database.execute(
                "UPDATE place_details SET cross_midnight = FALSE WHERE place_id = %d".formatted(placeId)
            ));
            assertRejected(() -> database.execute(
                "UPDATE place_details SET visit_minutes = 0 WHERE place_id = %d".formatted(placeId)
            ));
            assertRejected(() -> database.execute(
                """
                UPDATE place_details
                SET traits = '{"nature":1,"history":1,"local":1,"food":1,"festival":1,"record":1}'::jsonb
                WHERE place_id = %d
                """.formatted(placeId)
            ));
            assertRejected(() -> database.execute(
                """
                UPDATE place_details
                SET traits = '{"nature":false}'::jsonb
                WHERE place_id = %d
                """.formatted(placeId)
            ));

            long googleSourceId = database.insertAndReturnId(
                """
                INSERT INTO catalog_sources (source_key, name, provider_type, active)
                VALUES ('google-places', 'Google Places', 'google', TRUE)
                RETURNING id
                """
            );
            long googleLicenseId = database.insertAndReturnId(
                """
                INSERT INTO license_snapshots (
                    catalog_source_id, license_name, reviewed_at, valid_from,
                    allows_public_discovery, reusable_fields, digest
                )
                VALUES (%d, 'Google terms', CURRENT_TIMESTAMP, CURRENT_DATE - 1,
                    FALSE, '["image"]'::jsonb, 'license-digest-google')
                RETURNING id
                """.formatted(googleSourceId)
            );
            long googleRecordId = database.insertAndReturnId(
                """
                INSERT INTO place_source_records (
                    place_id, catalog_source_id, license_snapshot_id, external_id,
                    source_digest, source_updated_at, active, imported_at
                )
                VALUES (%d, %d, %d, 'google-id', 'source-digest-google', CURRENT_TIMESTAMP, FALSE,
                    CURRENT_TIMESTAMP)
                RETURNING id
                """.formatted(placeId, googleSourceId, googleLicenseId)
            );
            assertRejected(() -> database.execute(
                """
                INSERT INTO place_source_images (
                    place_source_record_id, source_image_id, license_snapshot_id,
                    source_digest, reusable
                )
                VALUES (%d, 'google-image', %d, 'image-digest-google', TRUE)
                """.formatted(googleRecordId, googleLicenseId)
            ));
        }
    }

    @Test
    void catalogFunctionDependenciesAreSchemaQualifiedForCleanRestore() throws Exception {
        try (MigrationDatabase database = MigrationDatabase.open()) {
            database.resetToEmptySchema();
            database.migrateTo(null);

            assertThat(database.scalarLong(
                """
                SELECT COUNT(*)
                FROM pg_proc function
                JOIN pg_namespace namespace ON namespace.oid = function.pronamespace
                WHERE namespace.nspname = current_schema()
                  AND (
                    (function.proname IN (
                        'catalog_schedule_is_valid',
                        'catalog_schedule_has_overnight_interval'
                    ) AND pg_get_functiondef(function.oid) LIKE
                        '%' || quote_ident(current_schema()) || '.catalog_time_is_valid%')
                    OR
                    (function.proname IN (
                        'catalog_validate_source_record_public_eligibility',
                        'catalog_validate_license_snapshot_public_eligibility'
                    ) AND pg_get_functiondef(function.oid) LIKE
                        '%' || quote_ident(current_schema()) || '.catalog_assert_public_place_eligibility%')
                  )
                """
            )).isEqualTo(4);
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

        long catalogFunctionCount() throws SQLException {
            return scalarLong(
                """
                SELECT COUNT(*)
                FROM pg_proc function
                JOIN pg_namespace namespace ON namespace.oid = function.pronamespace
                WHERE namespace.nspname = current_schema()
                  AND function.proname LIKE 'catalog_%'
                """
            );
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
