package com.stog.backend.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.HttpMethod;
import com.google.cloud.storage.Storage;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32C;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile({"gcs-read", "gcs-write"})
class GoogleCloudGcsObjectClient implements GcsObjectClient {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Storage storage;
    private final StorageProperties properties;
    private final GoogleCredentials credentials;
    private final URI apiOrigin;

    @Autowired
    GoogleCloudGcsObjectClient(
        Storage storage,
        StorageProperties properties,
        GoogleCredentials credentials
    ) {
        this(storage, properties, credentials, URI.create("https://storage.googleapis.com"));
    }

    GoogleCloudGcsObjectClient(
        Storage storage,
        StorageProperties properties,
        GoogleCredentials credentials,
        URI apiOrigin
    ) {
        this.storage = storage;
        this.properties = properties;
        this.credentials = credentials;
        this.apiOrigin = apiOrigin;
    }

    @Override
    public URI signPut(
        String objectKey,
        String contentType,
        String sha256,
        Map<String, String> metadata,
        Duration ttl
    ) {
        BlobInfo blob = BlobInfo.newBuilder(BlobId.of(properties.bucket(), objectKey))
            .setContentType(contentType)
            .setMetadata(metadata)
            .build();
        return URI.create(storage.signUrl(
            blob,
            ttl.toSeconds(),
            TimeUnit.SECONDS,
            Storage.SignUrlOption.httpMethod(HttpMethod.PUT),
            Storage.SignUrlOption.withContentType(),
            Storage.SignUrlOption.withExtHeaders(uploadHeaders(sha256, metadata)),
            Storage.SignUrlOption.withQueryParams(Map.of("ifGenerationMatch", "0")),
            Storage.SignUrlOption.withV4Signature()
        ).toString());
    }

    @Override
    public URI signRead(String objectKey, Duration ttl) {
        BlobInfo blob = BlobInfo.newBuilder(BlobId.of(properties.bucket(), objectKey))
            .build();
        return URI.create(storage.signUrl(
            blob,
            ttl.toSeconds(),
            TimeUnit.SECONDS,
            Storage.SignUrlOption.httpMethod(HttpMethod.GET),
            Storage.SignUrlOption.withV4Signature()
        ).toString());
    }

    @Override
    public Optional<ObjectMetadata> find(String objectKey) {
        HttpURLConnection connection = null;
        try {
            URI endpoint = apiEndpoint(
                "/storage/v1/b/"
                    + encode(properties.bucket())
                    + "/o/"
                    + encode(objectKey)
            );
            connection = (HttpURLConnection) endpoint.toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("Authorization", authorizationHeader());
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                return Optional.empty();
            }
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException(
                    "GCS object metadata request failed with HTTP " + status
                );
            }

            JsonNode object = OBJECT_MAPPER.readTree(connection.getInputStream());
            Map<String, String> metadata = object.has("metadata")
                ? OBJECT_MAPPER.convertValue(
                    object.get("metadata"),
                    new TypeReference<Map<String, String>>() { }
                )
                : Map.of();
            return Optional.of(new ObjectMetadata(
                object.path("name").asText(),
                object.path("contentType").asText(),
                Long.parseLong(object.path("size").asText("0")),
                object.path("crc32c").asText(),
                metadata
            ));
        } catch (IOException error) {
            throw new IllegalStateException("GCS object metadata could not be read", error);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @Override
    public ContentDigest digest(String objectKey, long maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("object digest bound must be positive");
        }
        HttpURLConnection connection = null;
        try {
            URI endpoint = apiEndpoint(
                "/download/storage/v1/b/"
                    + encode(properties.bucket())
                    + "/o/"
                    + encode(objectKey)
                    + "?alt=media"
            );
            connection = (HttpURLConnection) endpoint.toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("Authorization", authorizationHeader());
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException(
                    "GCS object content request failed with HTTP " + status
                );
            }
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            CRC32C crc32c = new CRC32C();
            long size = 0;
            byte[] buffer = new byte[8192];
            try (InputStream input = connection.getInputStream()) {
                while (true) {
                    int read = input.read(buffer);
                    if (read < 0) {
                        break;
                    }
                    size = Math.addExact(size, read);
                    if (size > maxBytes) {
                        throw new IllegalArgumentException("photo object exceeds configured size");
                    }
                    sha256.update(buffer, 0, read);
                    crc32c.update(buffer, 0, read);
                }
            }
            return new ContentDigest(
                size,
                java.util.HexFormat.of().formatHex(sha256.digest()),
                Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(Integer.BYTES).putInt((int) crc32c.getValue()).array()
                )
            );
        } catch (IOException | NoSuchAlgorithmException | ArithmeticException error) {
            throw new IllegalStateException("GCS object content could not be verified", error);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String authorizationHeader() throws IOException {
        credentials.refreshIfExpired();
        List<String> authorization = credentials.getRequestMetadata(
            URI.create("https://storage.googleapis.com/")
        ).get("Authorization");
        if (authorization == null || authorization.isEmpty()) {
            throw new IllegalStateException("GCS authorization header is unavailable");
        }
        return authorization.get(0);
    }

    private URI apiEndpoint(String pathAndQuery) {
        return URI.create(apiOrigin.toString().replaceAll("/+$", "") + pathAndQuery);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private Map<String, String> uploadHeaders(
        String sha256,
        Map<String, String> metadata
    ) {
        Map<String, String> headers = new java.util.HashMap<>();
        headers.put("x-goog-content-sha256", sha256);
        metadata.forEach((key, value) -> headers.put("x-goog-meta-" + key, value));
        return Map.copyOf(headers);
    }
}
