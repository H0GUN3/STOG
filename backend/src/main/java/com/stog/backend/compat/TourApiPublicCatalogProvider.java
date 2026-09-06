package com.stog.backend.compat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Adapter for the approved TourAPI JSON export contract. The export must contain
 * source, license_decision, complete, and task-8-compatible rows fields.
 * Optional source_images are accepted only as rights-annotated candidates; the
 * canonical importer applies the parent license and per-image reuse gates.
 */
@Component
@Profile("catalog-refresh")
public class TourApiPublicCatalogProvider implements CatalogRefreshProvider {
    private final RestClient client;
    private final ObjectMapper json = new ObjectMapper();
    private final TourApiCatalogProperties properties;

    public TourApiPublicCatalogProvider(
        @Qualifier("socialRestClientBuilder") RestClient.Builder builder,
        TourApiCatalogProperties properties
    ) {
        this.client = builder.requestFactory(requestFactory(properties)).build();
        this.properties = properties;
    }

    @Override
    public PublicCatalogSnapshot fetch(String runId) {
        URI endpoint = endpoint();
        try {
            String response = client.get()
                .uri(endpoint)
                .header("X-STOG-Catalog-Run", runId)
                .retrieve()
                .body(String.class);
            return parse(response);
        } catch (RestClientResponseException error) {
            String code = error.getStatusCode().value() == 429
                ? "quota_exhausted"
                : "provider_failure";
            throw new CatalogRefreshProviderException(code, error);
        } catch (RestClientException error) {
            throw new CatalogRefreshProviderException("provider_timeout", error);
        }
    }

    private URI endpoint() {
        if (properties.url() == null
            || !properties.url().isAbsolute()
            || properties.serviceKey() == null
            || properties.serviceKey().isBlank()) {
            throw new CatalogRefreshProviderException("provider_not_configured");
        }
        return UriComponentsBuilder.fromUri(properties.url())
            .queryParam("serviceKey", properties.serviceKey())
            .build()
            .toUri();
    }

    private PublicCatalogSnapshot parse(String response) {
        if (response == null || response.isBlank()) {
            throw new CatalogRefreshProviderException("malformed_response");
        }
        try {
            JsonNode root = json.readTree(response);
            JsonNode rows = root == null ? null : root.get("rows");
            if (root == null
                || !root.isObject()
                || !root.path("source").isTextual()
                || !root.path("license_decision").isTextual()
                || !root.path("complete").isBoolean()
                || rows == null
                || !rows.isArray()) {
                throw new CatalogRefreshProviderException("malformed_response");
            }
            List<CloudPlaceSourceRow> sourceRows = json.convertValue(rows, new TypeReference<>() {});
            return new PublicCatalogSnapshot(
                root.path("source").textValue(),
                root.path("license_decision").textValue(),
                root.path("complete").booleanValue(),
                sourceRows
            );
        } catch (CatalogRefreshProviderException error) {
            throw error;
        } catch (RuntimeException | java.io.IOException error) {
            throw new CatalogRefreshProviderException("malformed_response", error);
        }
    }

    private static SimpleClientHttpRequestFactory requestFactory(
        TourApiCatalogProperties properties
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout(properties.connectTimeout()));
        requestFactory.setReadTimeout(timeout(properties.readTimeout()));
        return requestFactory;
    }

    private static Duration timeout(Duration configured) {
        return configured == null ? Duration.ofSeconds(3) : configured;
    }
}
