package com.stog.backend.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.Storage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;

class GoogleCloudGcsObjectClientTest {
    private static final byte[] PAYLOAD = "server-side-photo-bytes"
        .getBytes(StandardCharsets.UTF_8);

    @Test
    void streamsAndHashesProviderBytesWithinTheConfiguredBound() throws Exception {
        try (ObjectApiServer server = new ObjectApiServer(PAYLOAD)) {
            GoogleCloudGcsObjectClient client = client(server.origin());

            GcsObjectClient.ObjectMetadata metadata = client.find("photo/original.jpg")
                .orElseThrow();
            GcsObjectClient.ContentDigest digest = client.digest(
                "photo/original.jpg",
                PAYLOAD.length
            );

            assertThat(metadata.sizeBytes()).isEqualTo(PAYLOAD.length);
            assertThat(metadata.crc32c()).isEqualTo(crc32c(PAYLOAD));
            assertThat(digest.sizeBytes()).isEqualTo(PAYLOAD.length);
            assertThat(digest.sha256()).isEqualTo(sha256(PAYLOAD));
            assertThat(digest.crc32c()).isEqualTo(metadata.crc32c());
            assertThat(server.authorization()).isEqualTo("Bearer task10-token");
        }
    }

    @Test
    void abortsTheProviderStreamWhenBytesExceedTheBound() throws Exception {
        try (ObjectApiServer server = new ObjectApiServer(PAYLOAD)) {
            GoogleCloudGcsObjectClient client = client(server.origin());

            assertThatThrownBy(() -> client.digest("photo/original.jpg", PAYLOAD.length - 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("configured size");
        }
    }

    private GoogleCloudGcsObjectClient client(URI origin) {
        GoogleCredentials credentials = GoogleCredentials.create(
            new AccessToken("task10-token", new Date(System.currentTimeMillis() + 3_600_000L))
        );
        return new GoogleCloudGcsObjectClient(
            mock(Storage.class),
            new StorageProperties(
                "task10-private",
                Duration.ofMinutes(5),
                "",
                org.springframework.util.unit.DataSize.ofMegabytes(2),
                org.springframework.util.unit.DataSize.ofKilobytes(50)
            ),
            credentials,
            origin
        );
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String crc32c(byte[] bytes) {
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, bytes.length);
        return Base64.getEncoder().encodeToString(
            ByteBuffer.allocate(Integer.BYTES).putInt((int) crc.getValue()).array()
        );
    }

    private static final class ObjectApiServer implements AutoCloseable {
        private final HttpServer server;
        private final byte[] payload;
        private volatile String authorization;

        private ObjectApiServer(byte[] payload) throws IOException {
            this.payload = payload.clone();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::respond);
            server.start();
        }

        private URI origin() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        private String authorization() {
            return authorization;
        }

        private void respond(HttpExchange exchange) throws IOException {
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (exchange.getRequestURI().getPath().startsWith("/download/")) {
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
            } else {
                byte[] body = ("""
                    {"name":"photo/original.jpg","contentType":"image/jpeg",\
                     "size":"%d","crc32c":"%s",\
                     "metadata":{"stog-derivative":"normalized-original"}}
                    """).formatted(payload.length, crc32c(payload))
                    .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
