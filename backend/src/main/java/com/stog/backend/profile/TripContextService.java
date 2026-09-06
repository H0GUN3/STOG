package com.stog.backend.profile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stog.backend.plan.TripMembershipPolicy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TripContextService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ALLOWED_KEYS = Set.of(
        "current_energy",
        "available_time",
        "weather",
        "crowd_level",
        "companion_type",
        "transport_available",
        "mobility_constraint",
        "trip_mode"
    );
    private static final TypeReference<Map<String, Object>> CONTEXT_TYPE =
        new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final TripMembershipPolicy memberships;

    public TripContextService(
        JdbcClient jdbc,
        TripMembershipPolicy memberships
    ) {
        this.jdbc = jdbc;
        this.memberships = memberships;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(long userId, long tripId) {
        memberships.requireActiveMember(userId, tripId);
        String value = jdbc.sql(
                "SELECT trip_context::text FROM trips WHERE id = :tripId"
            )
            .param("tripId", tripId)
            .query((row, rowNumber) -> {
                String context = row.getString("trip_context");
                return context == null ? "{}" : context;
            })
            .optional()
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Trip was not found"
            ));
        return parse(value);
    }

    @Transactional
    public Map<String, Object> replace(
        long userId,
        long tripId,
        Map<String, Object> values
    ) {
        Map<String, Object> current = get(userId, tripId);
        Map<String, Object> normalized = normalize(values);
        Map<String, Object> merged = new LinkedHashMap<>(current);
        merged.putAll(normalized);
        int updated = jdbc.sql(
                "UPDATE trips SET trip_context = CAST(:context AS jsonb) WHERE id = :tripId"
            )
            .param("context", write(merged))
            .param("tripId", tripId)
            .update();
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Trip was not found");
        }
        return Collections.unmodifiableMap(merged);
    }

    private static Map<String, Object> normalize(Map<String, Object> values) {
        if (values == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Trip context must be an object"
            );
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (!ALLOWED_KEYS.contains(entry.getKey())) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Trip context contains an unsupported key"
                );
            }
            normalized.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(normalized);
    }

    private String write(Map<String, Object> value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Trip context is not valid JSON",
                error
            );
        }
    }

    private Map<String, Object> parse(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return Collections.unmodifiableMap(
                new LinkedHashMap<>(JSON.readValue(value, CONTEXT_TYPE))
            );
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Stored trip context is invalid JSON", error);
        }
    }
}
