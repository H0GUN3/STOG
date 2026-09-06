package com.stog.backend.social;

import java.time.Instant;
import java.util.List;

public final class PublicTripResponses {
    private PublicTripResponses() {
    }

    public record Page(List<Item> items, String next_cursor) {
        public Page {
            items = List.copyOf(items);
        }
    }

    public record Item(
        long trip_id,
        long owner_id,
        String title,
        Instant ended_at,
        long like_count,
        boolean liked_by_viewer,
        long itinerary_item_count,
        List<MemberTrail> member_trails
    ) {
        public Item {
            member_trails = List.copyOf(member_trails);
        }
    }

    public record MemberTrail(long user_id, List<Visit> visits) {
        public MemberTrail {
            visits = List.copyOf(visits);
        }
    }

    public record Visit(
        long visit_id,
        String cell_id,
        double lat,
        double lng,
        Instant entered_at,
        Instant left_at,
        String status,
        boolean is_interpolated
    ) {
    }

    public record LikeState(long trip_id, long like_count, boolean liked_by_viewer) {
    }

    public record CopyResult(
        long source_trip_id,
        long destination_trip_id,
        int destination_day,
        int copied_item_count,
        List<Long> destination_basket_item_ids,
        List<Long> destination_itinerary_item_ids,
        long itinerary_change_id
    ) {
        public CopyResult {
            destination_basket_item_ids = List.copyOf(destination_basket_item_ids);
            destination_itinerary_item_ids = List.copyOf(destination_itinerary_item_ids);
        }
    }
}
