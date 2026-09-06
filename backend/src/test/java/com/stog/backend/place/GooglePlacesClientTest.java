package com.stog.backend.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.containsString;

import com.stog.backend.google.GoogleProviderException;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class GooglePlacesClientTest {
    private static final GooglePlacesProperties PROPERTIES =
        new GooglePlacesProperties(
            "places-key",
            URI.create("https://example.test/v1"),
            "ko",
            "KR"
        );

    @Test
    void textSearchMapsOnlyNormalizedPlaceFields() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GooglePlacesClient client = new GooglePlacesClient(
            builder,
            PROPERTIES,
            new ObjectMapper()
        );
        server.expect(requestTo("https://example.test/v1/places:searchText"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("X-Goog-Api-Key", "places-key"))
            .andExpect(header("X-Goog-FieldMask", containsString("places.rating")))
            .andExpect(header("X-Goog-FieldMask", containsString("places.userRatingCount")))
            .andExpect(header("X-Goog-FieldMask", containsString("places.currentOpeningHours.openNow")))
            .andExpect(request -> assertThat(
                request.getHeaders().getFirst("X-Goog-FieldMask")
            ).isNotBlank())
            .andRespond(withSuccess(
                """
                {
                  "places": [{
                    "id": "ChIJ123",
                    "displayName": {"text": "전주 한옥마을"},
                    "formattedAddress": "전북 전주시 완산구",
                    "location": {"latitude": 35.815, "longitude": 127.15},
                    "types": ["tourist_attraction"],
                    "rating": 4.7,
                    "userRatingCount": 1200,
                    "businessStatus": "OPERATIONAL",
                    "currentOpeningHours": {
                      "openNow": true,
                      "nextCloseTime": "2026-08-24T23:00:00+09:00"
                    },
                    "regularOpeningHours": {
                      "weekdayDescriptions": ["월요일: 오전 9:00~오후 6:00"]
                    },
                    "nationalPhoneNumber": "063-000-0000",
                    "websiteUri": "https://example.test",
                    "googleMapsUri": "https://maps.google.test/ChIJ123",
                    "photos": [{"name": "places/ChIJ123/photos/1"}]
                  }]
                }
                """,
                MediaType.APPLICATION_JSON
            ));

        var response = client.textSearch(new PlaceRequests.Search(
            "전주 한옥마을",
            35.815,
            127.15,
            3000.0,
            10
        ));

        assertThat(response).hasSize(1);
        assertThat(response.get(0).provider()).isEqualTo("google");
        assertThat(response.get(0).external_id()).isEqualTo("ChIJ123");
        assertThat(response.get(0).name()).isEqualTo("전주 한옥마을");
        assertThat(response.get(0).latitude()).isEqualTo(35.815);
        assertThat(response.get(0).rating()).isEqualTo(4.7);
        assertThat(response.get(0).user_rating_count()).isEqualTo(1200);
        assertThat(response.get(0).business_status()).isEqualTo("OPERATIONAL");
        assertThat(response.get(0).open_now()).isTrue();
        assertThat(response.get(0).next_close_time())
            .isEqualTo("2026-08-24T23:00:00+09:00");
        assertThat(response.get(0).photo_names())
            .containsExactly("places/ChIJ123/photos/1");
        assertThat(new ObjectMapper().writeValueAsString(response.get(0)))
            .contains("\"rating\":4.7");
        server.verify();
    }

    @Test
    void missingApiKeyFailsBeforeNetworkAccess() {
        GooglePlacesClient client = new GooglePlacesClient(
            RestClient.builder(),
            new GooglePlacesProperties(
                "",
                URI.create("https://example.test/v1"),
                "ko",
                "KR"
            ),
            new ObjectMapper()
        );

        assertThatThrownBy(() -> client.textSearch(new PlaceRequests.Search(
            "전주 한옥마을",
            null,
            null,
            null,
            null
        )))
            .isInstanceOf(GoogleProviderException.class)
            .hasMessage("GOOGLE_PLACES_SEARCH_FAILED_NOT_CONFIGURED");
    }

    @Test
    void locationBiasRequiresAllCoordinates() {
        GooglePlacesClient client = new GooglePlacesClient(
            RestClient.builder(),
            PROPERTIES,
            new ObjectMapper()
        );

        assertThatThrownBy(() -> client.textSearch(new PlaceRequests.Search(
            "카페",
            35.8,
            null,
            1000.0,
            null
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("provided together");
    }
}
