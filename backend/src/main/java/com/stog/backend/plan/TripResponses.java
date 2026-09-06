package com.stog.backend.plan;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class TripResponses {
    private TripResponses() {
    }

    public record Created(
        long id,
        String title,
        String activity_type,
        String mode,
        String visibility,
        LocalDate planned_start_date,
        LocalDate planned_end_date,
        URI cover_image_url,
        String region_code
    ) {
        public Created(
            long id,
            String title,
            String activity_type,
            String mode,
            String visibility
        ) {
            this(id, title, activity_type, mode, visibility, null, null, null, "JEONBUK");
        }

        public Created(
            long id,
            String title,
            String activity_type,
            String mode,
            String visibility,
            LocalDate planned_start_date,
            LocalDate planned_end_date,
            URI cover_image_url
        ) {
            this(
                id,
                title,
                activity_type,
                mode,
                visibility,
                planned_start_date,
                planned_end_date,
                cover_image_url,
                "JEONBUK"
            );
        }
    }

    public record Summary(
        long id,
        String title,
        String activity_type,
        String mode,
        String visibility,
        LocalDate planned_start_date,
        LocalDate planned_end_date,
        URI cover_image_url,
        String region_code
    ) {
        public Summary(
            long id,
            String title,
            String activity_type,
            String mode,
            String visibility
        ) {
            this(id, title, activity_type, mode, visibility, null, null, null, "JEONBUK");
        }

        public Summary(
            long id,
            String title,
            String activity_type,
            String mode,
            String visibility,
            LocalDate planned_start_date,
            LocalDate planned_end_date,
            URI cover_image_url
        ) {
            this(
                id,
                title,
                activity_type,
                mode,
                visibility,
                planned_start_date,
                planned_end_date,
                cover_image_url,
                "JEONBUK"
            );
        }
    }

    public record Home(
        long monthly_trip_count,
        long visited_cell_count,
        long saved_place_count,
        long monthly_received_like_count,
        List<Summary> trips
    ) {
        public Home {
            trips = List.copyOf(trips);
        }
    }

    public record Mode(
        long trip_id,
        String mode,
        Instant started_at,
        Instant ended_at
    ) {
    }

    public record Archive(
        long trip_id,
        String mode,
        LocalDate planned_start_date,
        LocalDate planned_end_date,
        Instant started_at,
        Instant ended_at,
        long itinerary_item_count,
        long itinerary_change_count,
        long visit_count,
        long visited_count,
        long passed_count,
        long visited_cell_count,
        long photo_count,
        List<ArchiveTrailPoint> trail
    ) {
    }

    public record ArchiveTrailPoint(
        long visit_id,
        long user_id,
        String cell_id,
        double latitude,
        double longitude,
        Instant entered_at,
        Instant left_at,
        String status,
        boolean is_interpolated
    ) {
    }
}
