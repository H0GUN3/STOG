package com.stog.backend.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

final class TravelGuideAiPlaceConstraints {
    private static final ObjectMapper JSON = new ObjectMapper();

    private TravelGuideAiPlaceConstraints() {
    }

    static boolean isOpenDuring(
        TravelGuideAiProposalRepository.BasketPlaceDetails place,
        LocalDate tripStartDate,
        int dayNumber,
        LocalTime arrival,
        int durationMinutes
    ) {
        if (place == null || place.openingHoursJson() == null
            || place.openingHoursJson().isBlank() || tripStartDate == null) {
            return true;
        }

        JsonNode schedule;
        try {
            schedule = JSON.readTree(place.openingHoursJson());
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Canonical opening schedule is invalid", error);
        }
        if (!schedule.isArray()) {
            throw new IllegalStateException("Canonical opening schedule must be an array");
        }

        DayOfWeek day = tripStartDate.plusDays(dayNumber - 1L).getDayOfWeek();
        for (JsonNode entry : schedule) {
            if (!day.name().equalsIgnoreCase(entry.path("day").asText())) {
                continue;
            }
            LocalTime opens = parseTime(entry.path("opens_at").asText());
            LocalTime closes = parseTime(entry.path("closes_at").asText());
            if (fits(openingMinute(opens), openingMinute(closes), arrival, durationMinutes)) {
                return true;
            }
        }
        return false;
    }

    private static boolean fits(
        int opens,
        int closes,
        LocalTime arrival,
        int durationMinutes
    ) {
        int start = openingMinute(arrival);
        int end = start + durationMinutes;
        if (closes >= opens) {
            return start >= opens && end <= closes;
        }
        if (start < closes) {
            start += 24 * 60;
            end += 24 * 60;
        }
        return start >= opens && end <= closes + 24 * 60;
    }

    private static int openingMinute(LocalTime value) {
        return value.getHour() * 60 + value.getMinute();
    }

    private static LocalTime parseTime(String value) {
        try {
            return LocalTime.parse(value);
        } catch (RuntimeException error) {
            throw new IllegalStateException("Canonical opening time is invalid", error);
        }
    }
}
