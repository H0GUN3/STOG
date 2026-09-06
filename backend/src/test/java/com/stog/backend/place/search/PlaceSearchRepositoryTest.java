package com.stog.backend.place.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PlaceSearchRepositoryTest {
    @Test
    void canonicalPhotoUrlsRemainInPhotoUrlsField() {
        var photoUrl = "https://cdn.example.test/cafe.jpg";
        var localResult = new PlaceSearchRepository.LocalResult(
            1L,
            "place-1",
            "Cafe",
            "Address",
            35.8,
            127.1,
            "CAFE",
            "google",
            2L,
            "ACTIVE",
            List.of(photoUrl)
        );

        var searchResult = localResult.toSearchResult();

        assertThat(searchResult.national_phone_number()).isNull();
        assertThat(searchResult.photo_names()).isEmpty();
        assertThat(searchResult.photo_urls()).containsExactly(photoUrl);
    }
}
