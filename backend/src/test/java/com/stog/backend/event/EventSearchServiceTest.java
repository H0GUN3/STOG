package com.stog.backend.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stog.backend.place.PlaceRequests;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class EventSearchServiceTest {
    @Test
    void returnsNormalizedNearbyEventsWithTourApiProvenance() {
        EventRepository repository = mock(EventRepository.class);
        when(repository.nearby(
            35.815,
            127.15,
            5_000.0,
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 10, 31),
            10
        )).thenReturn(List.of(new EventRepository.EventRow(
            "tour_api",
            "1001",
            "전주 비빔밥 축제",
            "전주월드컵경기장",
            "전북 전주시",
            35.846,
            127.126,
            LocalDate.of(2026, 10, 10),
            LocalDate.of(2026, 10, 12),
            null,
            "https://example.test/festival.jpg"
        )));

        EventSearchResponse response = new EventSearchService(
            repository,
            new EventSearchProperties(10_000.0, 10, 90)
        ).nearby(new EventRequests.Nearby(
            new PlaceRequests.Center(35.815, 127.15),
            5_000.0,
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 10, 31),
            null
        ));

        assertThat(response.events()).singleElement().satisfies(event -> {
            assertThat(event.title()).isEqualTo("전주 비빔밥 축제");
            assertThat(event.provenance().source()).isEqualTo("tour_api");
        });
    }

    @Test
    void rejectsAnInvertedDateRangeBeforeRepositoryAccess() {
        EventRepository repository = mock(EventRepository.class);

        assertThatThrownBy(() -> new EventSearchService(
            repository,
            new EventSearchProperties(10_000.0, 10, 90)
        ).nearby(new EventRequests.Nearby(
            new PlaceRequests.Center(35.815, 127.15),
            5_000.0,
            LocalDate.of(2026, 11, 1),
            LocalDate.of(2026, 10, 31),
            10
        )))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("to_date");

        verifyNoInteractions(repository);
    }
}
