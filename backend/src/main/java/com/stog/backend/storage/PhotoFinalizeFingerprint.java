package com.stog.backend.storage;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class PhotoFinalizeFingerprint {
    private PhotoFinalizeFingerprint() {
    }

    static String upload(
        long userId,
        PhotoRequests.UploadUrl request,
        String originalKey,
        String thumbKey
    ) {
        return sha256(
            Long.toString(userId),
            request.trip_id().toString(),
            request.client_upload_id(),
            originalKey,
            thumbKey,
            request.original().content_type(),
            request.original().size_bytes().toString(),
            request.original().sha256(),
            request.thumbnail().content_type(),
            request.thumbnail().size_bytes().toString(),
            request.thumbnail().sha256()
        );
    }

    static String finalizeRequest(long userId, PhotoRequests.Create request) {
        return sha256(
            upload(userId, request.uploadRequest(), request.original_key(), request.thumb_key()),
            request.source(),
            decimal(request.latitude()),
            decimal(request.longitude()),
            decimal(request.accuracy_m()),
            request.location_provenance(),
            request.taken_at() == null ? null : request.taken_at().toString(),
            request.caption(),
            request.place_resolution_status(),
            request.expected_place_id() == null ? null : request.expected_place_id().toString(),
            request.visibility(),
            request.public_consent() == null ? null : request.public_consent().toString()
        );
    }

    private static String decimal(Double value) {
        return value == null
            ? null
            : BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static String sha256(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                if (value == null) {
                    digest.update((byte) 0xff);
                    continue;
                }
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(bytes);
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
