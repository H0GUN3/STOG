package com.stog.backend.plan;

import java.util.Optional;

public final class EffectiveVisibility {
    /**
     * Canonical SQL predicate for public-photo reads, likes, and projections.
     * Callers use the fixed aliases {@code t} for trips and {@code p} for photos.
     */
    public static final String PUBLIC_PHOTO_ELIGIBILITY_SQL = publicPhotoEligibilitySql();
    public static final String FEED_PHOTO_ELIGIBILITY_SQL = publicPhotoEligibilitySql();
    public static final String LIKE_PHOTO_ELIGIBILITY_SQL = publicPhotoEligibilitySql();
    public static final String CELL_PHOTO_ELIGIBILITY_SQL = publicPhotoEligibilitySql();
    public static final String SIGNED_READ_PHOTO_ELIGIBILITY_SQL = publicPhotoEligibilitySql();
    public static final String CURRENT_PHOTO_PUBLICATION_STATUS_SQL = """
        CASE
            WHEN p.visibility = 'private' OR p.trip_id IS NULL THEN 'private'
            WHEN p.visibility = 'group' THEN
                CASE WHEN t.visibility = 'private' THEN 'private' ELSE 'group' END
            WHEN p.visibility <> 'public' OR t.visibility IS NULL THEN 'private'
            WHEN t.visibility <> 'public' THEN 'trip_not_public'
            WHEN p.moderation_status = 'blocked' THEN 'moderation_blocked'
            ELSE 'public'
        END
        """;

    private static String publicPhotoEligibilitySql() {
        return """
            t.visibility = 'public'
            AND p.visibility = 'public'
            AND p.moderation_status <> 'blocked'
            """;
    }

    private EffectiveVisibility() {
    }

    public static boolean isPubliclyEligible(
        String tripVisibility,
        String photoVisibility,
        String moderationStatus
    ) {
        return "public".equals(tripVisibility)
            && "public".equals(photoVisibility)
            && !"blocked".equals(moderationStatus);
    }

    public static Optional<Scope> stricter(String tripVisibility, String photoVisibility) {
        Optional<Scope> trip = Scope.from(tripVisibility);
        Optional<Scope> photo = Scope.from(photoVisibility);
        if (trip.isEmpty() || photo.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(trip.get().rank() <= photo.get().rank() ? trip.get() : photo.get());
    }

    public static boolean canReadTrip(
        String tripVisibility,
        boolean isOwner,
        boolean isActiveMember
    ) {
        Optional<Scope> scope = Scope.from(tripVisibility);
        if (scope.isEmpty()) {
            return false;
        }
        return switch (scope.get()) {
            case PRIVATE -> isOwner;
            case GROUP -> isOwner || isActiveMember;
            case PUBLIC -> true;
        };
    }

    public static boolean canReadPhoto(
        String tripVisibility,
        String photoVisibility,
        boolean isOwner,
        boolean isActiveMember,
        boolean isPubliclyEligible
    ) {
        Optional<Scope> scope = stricter(tripVisibility, photoVisibility);
        if (scope.isEmpty()) {
            return false;
        }
        if (isOwner) {
            return true;
        }
        return switch (scope.get()) {
            case PRIVATE -> false;
            case GROUP -> isActiveMember;
            case PUBLIC -> isActiveMember || isPubliclyEligible;
        };
    }

    public enum Scope {
        PRIVATE(0),
        GROUP(1),
        PUBLIC(2);

        private final int rank;

        Scope(int rank) {
            this.rank = rank;
        }

        int rank() {
            return rank;
        }

        static Optional<Scope> from(String value) {
            if (value == null) {
                return Optional.empty();
            }
            return switch (value) {
                case "private" -> Optional.of(PRIVATE);
                case "group" -> Optional.of(GROUP);
                case "public" -> Optional.of(PUBLIC);
                default -> Optional.empty();
            };
        }
    }
}
