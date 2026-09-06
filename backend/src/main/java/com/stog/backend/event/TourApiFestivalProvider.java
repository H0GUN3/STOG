package com.stog.backend.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

@Component
@Profile("event-refresh")
public class TourApiFestivalProvider {
    private static final DateTimeFormatter API_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter API_DATE_TIME =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final RestClient client;
    private final TourApiEventProperties properties;

    public TourApiFestivalProvider(
        @Qualifier("socialRestClientBuilder") RestClient.Builder builder,
        TourApiEventProperties properties
    ) {
        this.client = builder
            .requestFactory(requestFactory(properties))
            .build();
        this.properties = properties;
    }

    public List<TourApiFestival> fetch(LocalDate from, LocalDate to) {
        if (properties.url() == null
            || !properties.url().isAbsolute()
            || properties.serviceKey() == null
            || properties.serviceKey().isBlank()) {
            throw new TourApiEventProviderException("provider_not_configured");
        }

        List<TourApiFestival> festivals = new ArrayList<>();
        List<Integer> areas = properties.areaCodes().isEmpty()
            ? java.util.Collections.singletonList(null)
            : properties.areaCodes();
        for (Integer areaCode : areas) {
            fetchArea(festivals, areaCode, from, to);
        }
        return festivals;
    }

    static List<TourApiFestival> parse(String response) {
        try {
            JsonNode root = new ObjectMapper().readTree(response);
            JsonNode header = root.path("response").path("header");
            String resultCode = header.path("resultCode").asText("0000");
            if (!"0000".equals(resultCode)) {
                throw new TourApiEventProviderException(
                    "provider_error_" + resultCode
                );
            }

            JsonNode itemNode = root.path("response")
                .path("body")
                .path("items")
                .path("item");
            if (itemNode.isMissingNode() || itemNode.isNull()) {
                return List.of();
            }

            List<TourApiFestival> result = new ArrayList<>();
            if (itemNode.isArray()) {
                itemNode.forEach(item -> addIfValid(result, item));
            } else {
                addIfValid(result, itemNode);
            }
            return List.copyOf(result);
        } catch (TourApiEventProviderException error) {
            throw error;
        } catch (Exception error) {
            throw new TourApiEventProviderException("malformed_response", error);
        }
    }

    private void fetchArea(
        List<TourApiFestival> festivals,
        Integer areaCode,
        LocalDate from,
        LocalDate to
    ) {
        int page = 1;
        while (true) {
            String response = request(areaCode, from, to, page);
            List<TourApiFestival> pageItems = parse(response);
            if (pageItems.isEmpty()) {
                return;
            }
            festivals.addAll(pageItems);
            if (pageItems.size() < properties.pageSize()) {
                return;
            }
            page++;
        }
    }

    private String request(
        Integer areaCode,
        LocalDate from,
        LocalDate to,
        int page
    ) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromUri(properties.url())
            .queryParam("eventStartDate", API_DATE.format(from))
            .queryParam("eventEndDate", API_DATE.format(to))
            .queryParam("numOfRows", properties.pageSize())
            .queryParam("pageNo", page)
            .queryParam("MobileOS", "AND")
            .queryParam("MobileApp", "STOG")
            .queryParam("_type", "json")
            .queryParam("serviceKey", normalizeServiceKey(properties.serviceKey()));
        if (areaCode != null) {
            uri.queryParam("areaCode", areaCode);
        }

        try {
            return client.get()
                .uri(uri.build(true).toUri())
                .retrieve()
                .body(String.class);
        } catch (RestClientResponseException error) {
            String code = error.getStatusCode().value() == 429
                ? "quota_exhausted"
                : "provider_failure_" + error.getStatusCode().value();
            throw new TourApiEventProviderException(code, error);
        } catch (RestClientException error) {
            throw new TourApiEventProviderException("provider_timeout", error);
        }
    }

    static String normalizeServiceKey(String value) {
        String plusSafe = value.replace("+", "%2B");
        String decoded = URLDecoder.decode(plusSafe, StandardCharsets.UTF_8);
        return UriUtils.encodeQueryParam(decoded, StandardCharsets.UTF_8)
            .replace("+", "%2B");
    }

    private static void addIfValid(List<TourApiFestival> result, JsonNode item) {
        String externalId = text(item, "contentid");
        String title = text(item, "title");
        LocalDate startsOn = date(item, "eventstartdate");
        LocalDate endsOn = date(item, "eventenddate");
        Double longitude = number(item, "mapx");
        Double latitude = number(item, "mapy");
        if (externalId == null
            || title == null
            || startsOn == null
            || endsOn == null
            || endsOn.isBefore(startsOn)
            || latitude == null
            || longitude == null
            || latitude < -90.0
            || latitude > 90.0
            || longitude < -180.0
            || longitude > 180.0) {
            return;
        }
        result.add(new TourApiFestival(
            externalId,
            title,
            text(item, "eventplace"),
            joinAddress(text(item, "addr1"), text(item, "addr2")),
            latitude,
            longitude,
            startsOn,
            endsOn,
            null,
            text(item, "firstimage"),
            sourceUpdatedAt(text(item, "modifiedtime"))
        ));
    }

    private static String joinAddress(String first, String second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first + " " + second;
    }

    private static String text(JsonNode item, String field) {
        String value = item.path(field).asText("").trim();
        return value.isBlank() || "null".equalsIgnoreCase(value) ? null : value;
    }

    private static Double number(JsonNode item, String field) {
        String value = text(item, field);
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static LocalDate date(JsonNode item, String field) {
        String value = text(item, field);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value, API_DATE);
        } catch (DateTimeParseException error) {
            return null;
        }
    }

    private static OffsetDateTime sourceUpdatedAt(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, API_DATE_TIME).atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException error) {
            return null;
        }
    }

    private static SimpleClientHttpRequestFactory requestFactory(
        TourApiEventProperties properties
    ) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout(properties.connectTimeout()));
        factory.setReadTimeout(timeout(properties.readTimeout()));
        return factory;
    }

    private static Duration timeout(Duration configured) {
        return configured == null ? Duration.ofSeconds(3) : configured;
    }
}
