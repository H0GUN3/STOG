package com.stog.backend.compat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Map;

/** Converts the live source schedule object into the canonical detail shape. */
public final class CloudPlaceScheduleNormalizer {
    private CloudPlaceScheduleNormalizer() {
    }

    public static Schedule normalize(JsonNode source) {
        if (source == null || source.isNull()) {
            return new Schedule(null, null, null);
        }
        if (source.isArray()) {
            return new Schedule(source, null, overnight(source));
        }
        if (!source.isObject()) {
            return new Schedule(source, null, null);
        }

        ArrayNode weekly = JsonNodeFactory.instance.arrayNode();
        ArrayNode dateOverrides = JsonNodeFactory.instance.arrayNode();
        boolean hasSchedule = false;
        boolean hasOvernight = false;
        Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if ("date_overrides".equals(field.getKey())) {
                hasSchedule = true;
                hasOvernight |= appendDateOverrides(dateOverrides, field.getValue());
                continue;
            }
            hasSchedule = true;
            if (!field.getValue().isArray()) {
                return new Schedule(source, null, null);
            }
            for (JsonNode window : field.getValue()) {
                ObjectNode normalized = normalizeWindow(window);
                normalized.put("day", field.getKey());
                weekly.add(normalized);
                hasOvernight |= isOvernight(normalized);
            }
        }

        return new Schedule(
            weekly,
            dateOverrides,
            hasSchedule ? hasOvernight : null
        );
    }

    private static boolean appendDateOverrides(ArrayNode target, JsonNode value) {
        if (!value.isObject()) {
            return false;
        }
        boolean overnight = false;
        Iterator<Map.Entry<String, JsonNode>> dates = value.fields();
        while (dates.hasNext()) {
            Map.Entry<String, JsonNode> date = dates.next();
            JsonNode override = date.getValue();
            if (!override.isObject()) {
                return true;
            }
            boolean closed = override.path("closed").asBoolean(false);
            JsonNode windows = override.get("windows");
            if (closed && (windows == null || windows.isArray() && windows.isEmpty())) {
                ObjectNode normalized = JsonNodeFactory.instance.objectNode();
                normalized.put("date", date.getKey());
                normalized.put("closed", true);
                normalized.set("windows", JsonNodeFactory.instance.arrayNode());
                target.add(normalized);
                continue;
            }
            if (windows == null || !windows.isArray()) {
                return true;
            }
            for (JsonNode window : windows) {
                ObjectNode normalized = normalizeWindow(window);
                normalized.put("date", date.getKey());
                normalized.put("closed", false);
                target.add(normalized);
                overnight |= isOvernight(normalized);
            }
        }
        return overnight;
    }

    private static ObjectNode normalizeWindow(JsonNode source) {
        ObjectNode normalized = JsonNodeFactory.instance.objectNode();
        if (source.isObject()) {
            copy(source, normalized, "open", "opens_at");
            copy(source, normalized, "close", "closes_at");
            copy(source, normalized, "cross_midnight", "cross_midnight");
        }
        return normalized;
    }

    private static void copy(
        JsonNode source,
        ObjectNode target,
        String sourceName,
        String targetName
    ) {
        JsonNode value = source.get(sourceName);
        if (value != null) {
            target.set(targetName, value);
        }
    }

    private static boolean overnight(JsonNode schedule) {
        boolean result = false;
        for (JsonNode entry : schedule) {
            result |= isOvernight(entry);
        }
        return result;
    }

    private static boolean isOvernight(JsonNode window) {
        String open = window.path("opens_at").asText("");
        String close = window.path("closes_at").asText("");
        return !open.isBlank() && !close.isBlank() && open.compareTo(close) > 0;
    }

    public record Schedule(
        JsonNode weekly,
        JsonNode dateOverrides,
        Boolean crossMidnight
    ) {
    }
}
