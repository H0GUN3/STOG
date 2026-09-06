package com.stog.backend.compat;

import com.stog.backend.storage.GcsObjectClient;
import com.stog.backend.storage.StorageProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cloud Run Job command boundary for complete, licensed public catalog snapshots.
 * Cloud Scheduler invokes the deployed command; this application never schedules it.
 */
@Component
@Profile("local-import & catalog-refresh")
public class CatalogRefreshJob implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(CatalogRefreshJob.class);
    private static final Set<String> APPROVED_SOURCES = Set.of("TOUR_API", "AREA_RESTAURANT");
    private static final Set<String> APPROVED_LICENSES = Set.of(
        "APPROVED",
        "APPROVED_PUBLIC_REUSE",
        "PUBLIC_REUSE_APPROVED"
    );

    private final CatalogRefreshProvider provider;
    private final CloudPlaceDryRunService manifests;
    private final CanonicalPlaceImporter importer;
    private final Environment environment;
    private final TransactionTemplate transactions;
    private final JdbcClient jdbc;
    private final ObjectProvider<GcsObjectClient> gcsObjects;
    private final StorageProperties storageProperties;
    private final TourPhotoGalleryProperties photoProperties;

    public CatalogRefreshJob(
        CatalogRefreshProvider provider,
        CloudPlaceDryRunService manifests,
        CanonicalPlaceImporter importer,
        Environment environment,
        PlatformTransactionManager transactionManager,
        JdbcClient jdbc,
        ObjectProvider<GcsObjectClient> gcsObjects,
        StorageProperties storageProperties,
        TourPhotoGalleryProperties photoProperties
    ) {
        this.provider = provider;
        this.manifests = manifests;
        this.importer = importer;
        this.environment = environment;
        this.transactions = new TransactionTemplate(transactionManager);
        this.jdbc = jdbc;
        this.gcsObjects = gcsObjects;
        this.storageProperties = storageProperties;
        this.photoProperties = photoProperties;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!environment.getProperty("stog.catalog.refresh.run-on-startup", Boolean.class, false)) {
            return;
        }
        CatalogRefreshResult result = refresh();
        if (result.status() == CatalogRefreshResult.Status.FAILED) {
            throw new CatalogRefreshProviderException(result.failureCode());
        }
    }

    /**
     * Fetches and validates one complete source snapshot before the task-8/task-9
     * pipeline is entered. Any failure marks this run failed and rolls back its
     * canonical writes as one boundary.
     */
    public CatalogRefreshResult refresh() {
        CatalogRefreshResult result = transactions.execute(status -> {
            String runId = UUID.randomUUID().toString();
            try {
                PublicCatalogSnapshot snapshot = provider.fetch(runId);
                validateSnapshot(snapshot);
                CloudPlaceImportManifest manifest = manifests.previewImportManifestFixture(snapshot.rows());
                boolean imagesOnly = environment.getProperty(
                    "stog.catalog.refresh.images-only",
                    Boolean.class,
                    false
                );
                CanonicalPlaceImportResult imported = imagesOnly
                    ? importer.importImages(manifest)
                    : importer.importManifest(manifest);
                return CatalogRefreshResult.succeeded(
                    runId,
                    manifest.summary().sourceSnapshotDigest(),
                    imported
                );
            } catch (RuntimeException error) {
                status.setRollbackOnly();
                return CatalogRefreshResult.failed(runId, failureCode(error));
            }
        });
        if (result != null && result.status() != CatalogRefreshResult.Status.FAILED) {
            syncCatalogImages();
        }
        return result;
    }

    private void syncCatalogImages() {
        GcsObjectClient objects = gcsObjects.getIfAvailable();
        if (objects == null) {
            return;
        }
        List<CatalogImageRow> rows = jdbc.sql(
                """
                SELECT id, source_url
                FROM place_source_images
                WHERE object_key IS NULL
                  AND source_url IS NOT NULL
                ORDER BY id
                """
            )
            .query((row, rowNumber) -> new CatalogImageRow(
                row.getLong("id"),
                row.getString("source_url")
            ))
            .list();
        int failures = 0;
        for (CatalogImageRow row : rows) {
            try {
                syncCatalogImage(objects, row);
            } catch (RuntimeException error) {
                failures++;
                LOGGER.warn("Catalog image upload failed for source image {}", row.id(), error);
            }
        }
        if (failures > 0) {
            throw new CatalogRefreshProviderException("catalog_image_sync_failed");
        }
    }

    private void syncCatalogImage(GcsObjectClient objects, CatalogImageRow row) {
        String objectKey = "catalog-images/v1/source-images/" + row.id();
        if (objects.find(objectKey).isPresent()) {
            updateObjectKey(row.id(), objectKey);
            return;
        }
        DownloadedImage image = download(row.sourceUrl());
        String sha256 = sha256(image.bytes());
        Map<String, String> metadata = Map.of(
            "stog-media-type", "catalog-image",
            "stog-source-image-id", Long.toString(row.id()),
            "stog-size", Integer.toString(image.bytes().length),
            "stog-sha256", sha256
        );
        URI uploadUrl = objects.signPut(
            objectKey,
            image.contentType(),
            sha256,
            metadata,
            requiredSignedUrlTtl()
        );
        put(uploadUrl, image, sha256, metadata);
        GcsObjectClient.ObjectMetadata stored = objects.find(objectKey)
            .orElseThrow(() -> new IllegalStateException("uploaded catalog image is missing"));
        if (!objectKey.equals(stored.objectKey())
            || !image.contentType().equals(stored.contentType())
            || stored.sizeBytes() != image.bytes().length
            || !metadata.equals(stored.metadata())) {
            throw new IllegalStateException("uploaded catalog image metadata is invalid");
        }
        GcsObjectClient.ContentDigest digest = objects.digest(
            objectKey,
            catalogImageMaxBytes()
        );
        if (!sha256.equals(digest.sha256()) || digest.sizeBytes() != image.bytes().length) {
            throw new IllegalStateException("uploaded catalog image digest is invalid");
        }
        updateObjectKey(row.id(), objectKey);
    }

    private void updateObjectKey(long sourceImageId, String objectKey) {
        jdbc.sql(
                """
                UPDATE place_source_images
                SET object_key = :objectKey
                WHERE id = :sourceImageId
                  AND object_key IS NULL
                """
            )
            .param("sourceImageId", sourceImageId)
            .param("objectKey", objectKey)
            .update();
    }

    private DownloadedImage download(String sourceUrl) {
        URI current = requireHttpsUri(sourceUrl);
        for (int redirects = 0; redirects < 4; redirects++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) current.toURL().openConnection();
                connection.setRequestMethod("GET");
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(timeoutMillis(photoProperties.connectTimeout()));
                connection.setReadTimeout(timeoutMillis(photoProperties.readTimeout()));
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.isBlank()) {
                        throw new IllegalStateException("catalog image redirect has no location");
                    }
                    current = requireHttpsUri(current.resolve(location).toString());
                    continue;
                }
                if (status < 200 || status > 299) {
                    throw new IllegalStateException("catalog image download failed with HTTP " + status);
                }
                String contentType = imageContentType(connection.getContentType());
                byte[] bytes = boundedBytes(connection.getInputStream(), catalogImageMaxBytes());
                return new DownloadedImage(bytes, contentType);
            } catch (IOException error) {
                throw new IllegalStateException("catalog image download failed", error);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
        throw new IllegalStateException("catalog image redirect limit exceeded");
    }

    private void put(
        URI uploadUrl,
        DownloadedImage image,
        String sha256,
        Map<String, String> metadata
    ) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) uploadUrl.toURL().openConnection();
            connection.setRequestMethod("PUT");
            connection.setDoOutput(true);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            connection.setFixedLengthStreamingMode(image.bytes().length);
            connection.setRequestProperty("Content-Type", image.contentType());
            connection.setRequestProperty("x-goog-content-sha256", sha256);
            for (Map.Entry<String, String> entry : metadata.entrySet()) {
                connection.setRequestProperty("x-goog-meta-" + entry.getKey(), entry.getValue());
            }
            try (var output = connection.getOutputStream()) {
                output.write(image.bytes());
            }
            int status = connection.getResponseCode();
            if (status < 200 || status > 299) {
                throw new IllegalStateException("catalog image upload failed with HTTP " + status);
            }
        } catch (IOException error) {
            throw new IllegalStateException("catalog image upload failed", error);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private byte[] boundedBytes(InputStream input, long maxBytes) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new IllegalStateException("catalog image exceeds configured size");
                }
                output.write(buffer, 0, read);
            }
            if (total == 0) {
                throw new IllegalStateException("catalog image is empty");
            }
            return output.toByteArray();
        }
    }

    private String imageContentType(String value) {
        if (value == null || !value.toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw new IllegalStateException("catalog image content type is unsupported");
        }
        return value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private URI requireHttpsUri(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getHost().isBlank()) {
                throw new IllegalStateException("catalog image URL must be HTTPS");
            }
            return uri;
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("catalog image URL is invalid", error);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes)
            );
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private Duration requiredSignedUrlTtl() {
        Duration ttl = storageProperties.signedUrlTtl();
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalStateException("GCS signed URL TTL is not configured");
        }
        return ttl;
    }

    private long catalogImageMaxBytes() {
        if (storageProperties.originalMaxSize() == null
            || storageProperties.originalMaxSize().toBytes() < 1) {
            throw new IllegalStateException("catalog image size limit is not configured");
        }
        return storageProperties.originalMaxSize().toBytes();
    }

    private int timeoutMillis(Duration timeout) {
        long millis = timeout.toMillis();
        return (int) Math.min(Math.max(millis, 1L), Integer.MAX_VALUE);
    }

    private record CatalogImageRow(long id, String sourceUrl) {
    }

    private record DownloadedImage(byte[] bytes, String contentType) {
    }

    private static void validateSnapshot(PublicCatalogSnapshot snapshot) {
        if (snapshot == null || snapshot.rows() == null || snapshot.rows().stream().anyMatch(row -> row == null)) {
            throw new CatalogRefreshProviderException("malformed_response");
        }
        String source = requiredApprovedSource(snapshot.source());
        requireApprovedLicense(snapshot.licenseDecision());
        for (CloudPlaceSourceRow row : snapshot.rows()) {
            if (!source.equals(requiredApprovedSource(row.source()))) {
                throw new CatalogRefreshProviderException("malformed_response");
            }
            requireApprovedLicense(row.licenseDecision());
        }
        if (!snapshot.complete()) {
            throw new CatalogRefreshProviderException("incomplete_page");
        }
    }

    private static String requiredApprovedSource(String source) {
        if (source == null || !APPROVED_SOURCES.contains(source.trim().toUpperCase(Locale.ROOT))) {
            throw new CatalogRefreshProviderException("unapproved_source");
        }
        return source.trim().toUpperCase(Locale.ROOT);
    }

    private static void requireApprovedLicense(String licenseDecision) {
        if (licenseDecision == null
            || !APPROVED_LICENSES.contains(licenseDecision.trim().toUpperCase(Locale.ROOT))) {
            throw new CatalogRefreshProviderException("unknown_license");
        }
    }

    private static String failureCode(RuntimeException error) {
        if (error instanceof CatalogRefreshProviderException providerError) {
            return providerError.code();
        }
        if (error instanceof CanonicalPlaceImporter.ImportRejectedException rejected) {
            return rejected.reason();
        }
        return "refresh_failed";
    }
}
