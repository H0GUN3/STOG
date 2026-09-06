package com.stog.backend.compat;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("cloud-write")
public class CloudPlaceWriteRepository {
    private static final String FIND_GOOGLE_ITEM_SQL = """
        SELECT travel_item_id
        FROM travel_item_external_refs
        WHERE provider = 'GOOGLE' AND external_id = :externalId
        """;
    private static final String INSERT_ITEM_SQL = """
        INSERT INTO travel_items (
            id,
            item_type,
            name,
            location,
            address,
            website_url,
            source,
            source_item_id,
            status,
            environment_type,
            estimated_cost,
            source_updated_at,
            last_synced_at
        )
        VALUES (
            :travelItemId,
            :itemType,
            :name,
            CASE
                WHEN :latitude IS NULL OR :longitude IS NULL THEN NULL
                ELSE ST_SetSRID(
                    ST_MakePoint(
                        CAST(:longitude AS double precision),
                        CAST(:latitude AS double precision)
                    ),
                    4326
                )::geography
            END,
            :address,
            :websiteUri,
            :source,
            :sourceItemId,
            'ACTIVE',
            :environmentType,
            :estimatedCost,
            CURRENT_TIMESTAMP,
            CURRENT_TIMESTAMP
        )
        """;
    private static final String UPDATE_ITEM_SQL = """
        UPDATE travel_items
        SET item_type = :itemType,
            name = :name,
            location = CASE
                WHEN :latitude IS NULL OR :longitude IS NULL THEN location
                ELSE ST_SetSRID(
                    ST_MakePoint(
                        CAST(:longitude AS double precision),
                        CAST(:latitude AS double precision)
                    ),
                    4326
                )::geography
            END,
            address = COALESCE(:address, address),
            website_url = COALESCE(:websiteUri, website_url),
            source = :source,
            source_item_id = :sourceItemId,
            source_updated_at = CURRENT_TIMESTAMP,
            last_synced_at = CURRENT_TIMESTAMP,
            updated_at = CURRENT_TIMESTAMP,
            environment_type = :environmentType,
            estimated_cost = :estimatedCost
        WHERE id = :travelItemId
        """;
    private static final String INSERT_EXTERNAL_REF_SQL = """
        INSERT INTO travel_item_external_refs (
            travel_item_id,
            provider,
            external_id
        )
        VALUES (:travelItemId, 'GOOGLE', :externalId)
        ON CONFLICT (provider, external_id) DO NOTHING
        """;
    private static final String DELETE_NEW_ITEM_SQL = """
        DELETE FROM travel_items WHERE id = :travelItemId
        """;

    private final JdbcClient jdbc;

    public CloudPlaceWriteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public CloudPlaceUpsertResult upsert(CloudPlaceUpsertCommand command) {
        String externalId = command.candidate().external_id();
        String existingId = findGoogleItemId(externalId);
        if (existingId != null) {
            updateItem(existingId, command);
            return new CloudPlaceUpsertResult(existingId, false);
        }

        String newId = UUID.randomUUID().toString();
        insertItem(newId, command);
        int refRows = insertExternalRef(newId, externalId);
        if (refRows == 1) {
            return new CloudPlaceUpsertResult(newId, true);
        }

        /*
         * A concurrent request won the unique provider/external_id race.
         * Remove only this transaction's unreferenced row, then update the winner.
         */
        jdbc.sql(DELETE_NEW_ITEM_SQL)
            .param("travelItemId", newId)
            .update();
        String winnerId = findGoogleItemId(externalId);
        if (winnerId == null) {
            throw new IllegalStateException("Google external reference disappeared");
        }
        updateItem(winnerId, command);
        return new CloudPlaceUpsertResult(winnerId, false);
    }

    private String findGoogleItemId(String externalId) {
        return jdbc.sql(FIND_GOOGLE_ITEM_SQL)
            .param("externalId", externalId)
            .query(String.class)
            .list()
            .stream()
            .findFirst()
            .orElse(null);
    }

    private void insertItem(String travelItemId, CloudPlaceUpsertCommand command) {
        jdbc.sql(INSERT_ITEM_SQL)
            .params(parameters(travelItemId, command))
            .update();
    }

    private int insertExternalRef(String travelItemId, String externalId) {
        return jdbc.sql(INSERT_EXTERNAL_REF_SQL)
            .param("travelItemId", travelItemId)
            .param("externalId", externalId)
            .update();
    }

    private void updateItem(String travelItemId, CloudPlaceUpsertCommand command) {
        jdbc.sql(UPDATE_ITEM_SQL)
            .params(parameters(travelItemId, command))
            .update();
    }

    private static java.util.Map<String, Object> parameters(
        String travelItemId,
        CloudPlaceUpsertCommand command
    ) {
        var candidate = command.candidate();
        var parameters = new java.util.HashMap<String, Object>();
        parameters.put("travelItemId", travelItemId);
        parameters.put("itemType", command.itemType());
        parameters.put("name", candidate.name());
        parameters.put("latitude", candidate.latitude());
        parameters.put("longitude", candidate.longitude());
        parameters.put("address", candidate.formatted_address());
        parameters.put("websiteUri", candidate.website_uri());
        parameters.put("source", command.source());
        parameters.put("sourceItemId", candidate.external_id());
        parameters.put("environmentType", command.environmentType());
        parameters.put("estimatedCost", command.estimatedCost());
        return parameters;
    }
}
