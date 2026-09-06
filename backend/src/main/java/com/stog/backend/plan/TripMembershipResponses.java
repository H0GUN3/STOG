package com.stog.backend.plan;

import java.time.Instant;
import java.util.List;

public final class TripMembershipResponses {
    private TripMembershipResponses() {
    }

    public record InviteLink(String token, String join_path) {
    }

    public record Joined(long trip_id) {
    }

    public record Members(long viewer_id, long owner_id, List<Member> members) {
    }

    public record Member(long user_id, String nickname, Instant joined_at) {
    }
}
