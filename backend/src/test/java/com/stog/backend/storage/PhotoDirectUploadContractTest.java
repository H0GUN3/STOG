package com.stog.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;

class PhotoDirectUploadContractTest {
    private static final String UPLOAD_ID = "22222222-2222-2222-2222-222222222222";
    private static final byte[] ORIGINAL = "normalized-photo-payload".getBytes(StandardCharsets.UTF_8);
    private static final byte[] THUMBNAIL = "thumbnail-photo-payload".getBytes(StandardCharsets.UTF_8);

    @Test
    void signedPutPairVerifiesActualPayloadAndProducesSignedReads() throws Exception {
        try (HttpStorageSurface surface = new HttpStorageSurface()) {
            GcsSignedUrlService service = service(surface);
            PhotoRequests.UploadUrl request = request();
            PhotoResponses.UploadUrl urls = service.issueUploadUrl("42", request);

            assertThat(put(urls.original_upload_url(), urls.original_upload_headers(), ORIGINAL))
                .isEqualTo(200);
            assertThat(put(urls.thumbnail_upload_url(), urls.thumbnail_upload_headers(), THUMBNAIL))
                .isEqualTo(200);

            service.verifyUploadedObjects(
                "42",
                request,
                urls.original_object_key(),
                urls.thumbnail_object_key()
            );
            PhotoResponses.ReadUrls reads = service.issueReadUrls(
                urls.original_object_key(),
                urls.thumbnail_object_key()
            );

            assertThat(get(reads.original_url())).isEqualTo(ORIGINAL);
            assertThat(get(reads.thumbnail_url())).isEqualTo(THUMBNAIL);
            assertThat(surface.objectCount()).isEqualTo(2);
        }
    }

    @Test
    void missingSecondObjectAndWrongPayloadDigestFailClosed() throws Exception {
        try (HttpStorageSurface surface = new HttpStorageSurface()) {
            GcsSignedUrlService service = service(surface);
            PhotoRequests.UploadUrl request = request();
            PhotoResponses.UploadUrl urls = service.issueUploadUrl("42", request);

            assertThat(put(urls.original_upload_url(), urls.original_upload_headers(), ORIGINAL))
                .isEqualTo(200);
            assertThatThrownBy(() -> service.verifyUploadedObjects(
                "42", request, urls.original_object_key(), urls.thumbnail_object_key()
            )).isInstanceOf(IllegalArgumentException.class);

            PhotoResponses.UploadUrl second = service.issueUploadUrl("42", request());
            assertThat(put(
                second.thumbnail_upload_url(),
                second.thumbnail_upload_headers(),
                "wrong-payload".getBytes(StandardCharsets.UTF_8)
            )).isEqualTo(400);
            assertThat(surface.objectCount()).isEqualTo(1);
        }
    }

    private int put(URI uri, Map<String, String> headers, byte[] bytes) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(5))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
        headers.forEach(request::header);
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()
            .send(request.build(), HttpResponse.BodyHandlers.discarding())
            .statusCode();
    }

    private byte[] get(URI uri) throws Exception {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()
            .send(
                HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()
            )
            .body();
    }

    private PhotoRequests.UploadUrl request() throws Exception {
        return new PhotoRequests.UploadUrl(
            77L,
            UPLOAD_ID,
            new PhotoRequests.UploadObject("image/jpeg", (long) ORIGINAL.length, sha256(ORIGINAL)),
            new PhotoRequests.UploadObject("image/jpeg", (long) THUMBNAIL.length, sha256(THUMBNAIL))
        );
    }

    private GcsSignedUrlService service(GcsObjectClient objects) {
        return new GcsSignedUrlService(
            objects,
            new StorageProperties("local-private-fake", Duration.ofMinutes(5), "",
                org.springframework.util.unit.DataSize.ofMegabytes(2),
                org.springframework.util.unit.DataSize.ofKilobytes(50)),
            Clock.fixed(Instant.parse("2026-08-25T00:00:00Z"), ZoneOffset.UTC)
        );
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static final class HttpStorageSurface implements GcsObjectClient, AutoCloseable {
        private final HttpServer server;
        private final Map<String, ExpectedPut> expected = new HashMap<>();
        private final Map<String, ObjectMetadata> metadata = new HashMap<>();
        private final Map<String, byte[]> bytes = new HashMap<>();

        private HttpStorageSurface() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/upload", this::upload);
            server.createContext("/read", this::read);
            server.start();
        }

        @Override
        public URI signPut(
            String objectKey,
            String contentType,
            String sha256,
            Map<String, String> objectMetadata,
            Duration ttl
        ) {
            expected.put(objectKey, new ExpectedPut(contentType, sha256, objectMetadata));
            return endpoint("upload", objectKey);
        }

        @Override
        public URI signRead(String objectKey, Duration ttl) {
            return endpoint("read", objectKey);
        }

        @Override
        public Optional<ObjectMetadata> find(String objectKey) {
            return Optional.ofNullable(metadata.get(objectKey));
        }

        @Override
        public ContentDigest digest(String objectKey, long maxBytes) {
            byte[] payload = bytes.get(objectKey);
            if (payload == null || payload.length > maxBytes) {
                throw new IllegalArgumentException("object content is unavailable or oversized");
            }
            return new ContentDigest(payload.length, uncheckedSha256(payload), crc32c(payload));
        }

        private void upload(HttpExchange exchange) throws IOException {
            String key = key(exchange);
            ExpectedPut policy = expected.get(key);
            byte[] payload = exchange.getRequestBody().readAllBytes();
            boolean valid = policy != null
                && "PUT".equals(exchange.getRequestMethod())
                && policy.contentType().equals(exchange.getRequestHeaders().getFirst("Content-Type"))
                && policy.sha256().equals(
                    exchange.getRequestHeaders().getFirst("x-goog-content-sha256")
                )
                && policy.sha256().equals(uncheckedSha256(payload));
            if (valid) {
                for (Map.Entry<String, String> entry : policy.metadata().entrySet()) {
                    valid &= entry.getValue().equals(exchange.getRequestHeaders().getFirst(
                        "x-goog-meta-" + entry.getKey()
                    ));
                }
            }
            if (valid) {
                bytes.put(key, payload);
                metadata.put(key, new ObjectMetadata(
                    key,
                    policy.contentType(),
                    payload.length,
                    crc32c(payload),
                    policy.metadata()
                ));
            }
            exchange.sendResponseHeaders(valid ? 200 : 400, -1);
            exchange.close();
        }

        private void read(HttpExchange exchange) throws IOException {
            byte[] payload = bytes.get(key(exchange));
            if (payload == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
            }
            exchange.close();
        }

        private URI endpoint(String operation, String objectKey) {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                + "/" + operation + "?key="
                + URLEncoder.encode(objectKey, StandardCharsets.UTF_8));
        }

        private String key(HttpExchange exchange) {
            return URLDecoder.decode(
                exchange.getRequestURI().getRawQuery().substring("key=".length()),
                StandardCharsets.UTF_8
            );
        }

        private int objectCount() {
            return metadata.size();
        }

        @Override
        public void close() {
            server.stop(0);
            expected.clear();
            metadata.clear();
            bytes.clear();
        }

        private static String uncheckedSha256(byte[] payload) {
            try {
                return sha256(payload);
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        }

        private static String crc32c(byte[] payload) {
            CRC32C crc = new CRC32C();
            crc.update(payload, 0, payload.length);
            return Base64.getEncoder().encodeToString(
                ByteBuffer.allocate(Integer.BYTES).putInt((int) crc.getValue()).array()
            );
        }

        private record ExpectedPut(
            String contentType,
            String sha256,
            Map<String, String> metadata
        ) {
            private ExpectedPut {
                metadata = Map.copyOf(metadata);
            }
        }
    }
}
