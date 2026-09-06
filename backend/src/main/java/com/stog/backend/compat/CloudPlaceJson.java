package com.stog.backend.compat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;

final class CloudPlaceJson {
    private static final ObjectMapper JSON = JsonMapper.builder()
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .build();

    private CloudPlaceJson() {
    }

    static JsonNode stable(JsonNode value) {
        if (value == null) {
            return null;
        }
        if (value.isObject()) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            ArrayList<String> fields = new ArrayList<>();
            value.fieldNames().forEachRemaining(fields::add);
            fields.stream().sorted().forEach(field -> result.set(field, stable(value.get(field))));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            for (JsonNode element : value) {
                result.add(stable(element));
            }
            return result;
        }
        return value.deepCopy();
    }

    static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("manifest JSON serialization failed", error);
        }
    }

    static String sha256(Object value) {
        return sha256(json(value));
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte current : digest) {
                result.append(String.format("%02x", current));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
