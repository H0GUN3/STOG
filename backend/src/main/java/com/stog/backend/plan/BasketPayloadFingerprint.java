package com.stog.backend.plan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class BasketPayloadFingerprint {
    private BasketPayloadFingerprint() {
    }

    public static String forPlace(BasketRequests.AddPlace request) {
        return forPlace(
            request.trip_id(), request.provider(), request.external_id(), request.name(),
            request.category(), request.latitude(), request.longitude(),
            request.canonical_place_id(), request.canonical_source_id()
        );
    }

    public static String forPlace(
        Long tripId,
        String provider,
        String externalId,
        String name,
        String category,
        Double latitude,
        Double longitude,
        Long canonicalPlaceId,
        Long canonicalSourceId
    ) {
        return sha256(
            tripId, provider, externalId, name, category, latitude, longitude,
            canonicalPlaceId, canonicalSourceId
        );
    }

    public static String forLink(BasketRequests.AddLink request) {
        return forLink(
            request.trip_id(), request.source(), request.original_url(), request.title(),
            request.category()
        );
    }

    public static String forLink(
        Long tripId,
        String source,
        String originalUrl,
        String title,
        String category
    ) {
        return sha256(tripId, source, originalUrl, title, category);
    }

    private static String sha256(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                byte[] bytes = value == null
                    ? new byte[0]
                    : value.toString().getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(bytes);
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
