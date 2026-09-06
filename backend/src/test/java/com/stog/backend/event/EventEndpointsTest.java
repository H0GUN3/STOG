package com.stog.backend.event;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stog.backend.place.GooglePlacesClient;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@Transactional
@Import(EventEndpointsTest.TestGooglePlacesConfiguration.class)
class EventEndpointsTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void guestsCanReadNearbyEventsWithoutAuthentication() throws Exception {
        jdbc.sql("""
                INSERT INTO events (
                    provider,
                    external_id,
                    title,
                    venue_name,
                    formatted_address,
                    latitude,
                    longitude,
                    starts_on,
                    ends_on,
                    image_uri
                )
                VALUES (
                    :provider,
                    :externalId,
                    :title,
                    :venueName,
                    :address,
                    :latitude,
                    :longitude,
                    :startsOn,
                    :endsOn,
                    :imageUri
                )
                """)
            .params(Map.of(
                "provider", "tour_api",
                "externalId", "event-1001",
                "title", "전주 비빔밥 축제",
                "venueName", "전주월드컵경기장",
                "address", "전북 전주시",
                "latitude", 35.846,
                "longitude", 127.126,
                "startsOn", java.time.LocalDate.of(2026, 10, 10),
                "endsOn", java.time.LocalDate.of(2026, 10, 12),
                "imageUri", "https://example.test/festival.jpg"
            ))
            .update();

        mockMvc.perform(post("/events/nearby")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "center": {"latitude": 35.815, "longitude": 127.15},
                      "radius_meters": 10000,
                      "from_date": "2026-10-01",
                      "to_date": "2026-10-31",
                      "max_result_count": 10
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.events[0].title").value("전주 비빔밥 축제"))
            .andExpect(jsonPath("$.events[0].provider").value("tour_api"))
            .andExpect(jsonPath("$.events[0].provenance.source").value("tour_api"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestGooglePlacesConfiguration {
        @Bean
        @Primary
        GooglePlacesClient googlePlacesClient() {
            return mock(GooglePlacesClient.class);
        }
    }
}
