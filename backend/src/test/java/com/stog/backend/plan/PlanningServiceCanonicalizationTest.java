package com.stog.backend.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stog.backend.place.GooglePlacesClient;
import com.stog.backend.place.PlaceCandidate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlanningServiceCanonicalizationTest {
    @Mock
    private TripRepository trips;

    @Mock
    private TripMembershipPolicy memberships;

    @Mock
    private PlaceRepository places;

    @Mock
    private BasketItemRepository basketItems;

    @Mock
    private GooglePlacesClient googlePlaces;

    @Test
    void confirmationUsesGoogleDetailsRatherThanClientSuppliedPlaceData() {
        PlanningService planning = new PlanningService(
            trips,
            memberships,
            places,
            basketItems,
            googlePlaces
        );
        BasketRequests.AddPlace clientRequest = new BasketRequests.AddPlace(
            123L,
            "google",
            "ChIJverified",
            "client supplied name",
            "client-supplied-category",
            0.0,
            0.0
        );
        PlaceCandidate canonicalCandidate = new PlaceCandidate(
            "google",
            "ChIJverified",
            "canonical Google place",
            "canonical address",
            35.815,
            127.15,
            List.of("cafe"),
            List.of(),
            null,
            null,
            null,
            List.of()
        );
        when(googlePlaces.details("ChIJverified")).thenReturn(canonicalCandidate);
        when(places.upsertPrivateReference(any())).thenReturn(new PlaceRepository.Upserted(
            77L,
            null
        ));
        when(basketItems.addPlace(eq(44L), eq(123L), any(), any())).thenReturn(88L);

        BasketResponses.Added result = planning.addPlace(44L, clientRequest);

        ArgumentCaptor<BasketRequests.AddPlace> request = ArgumentCaptor.forClass(
            BasketRequests.AddPlace.class
        );
        verify(googlePlaces).details("ChIJverified");
        verify(places).upsertPrivateReference(request.capture());
        assertThat(request.getValue())
            .usingRecursiveComparison()
            .isEqualTo(new BasketRequests.AddPlace(
                123L,
                clientRequest.client_item_id(),
                clientRequest.payload_fingerprint(),
                "google",
                "ChIJverified",
                "canonical Google place",
                "cafe",
                35.815,
                127.15,
                null,
                null
            ));
        assertThat(result.place_id()).isEqualTo(77L);
        assertThat(result.cell_id()).isNull();
    }
}
