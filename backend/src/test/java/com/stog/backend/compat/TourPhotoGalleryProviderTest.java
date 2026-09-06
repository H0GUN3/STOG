package com.stog.backend.compat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TourPhotoGalleryProviderTest {
    @Test
    void parsesAndMatchesGalleryImageToPlaceName() {
        var images = TourPhotoGalleryProvider.parseImages("""
            {
              "response": {
                "header": {"resultCode": "0000"},
                "body": {
                  "items": {
                    "item": {
                      "galContentId": "gallery-1",
                      "galTitle": "전주 한옥마을의 봄",
                      "galPhotographyLocation": "전주 한옥마을",
                      "galWebImageUrl": "https://images.example.test/hanok.jpg"
                    }
                  }
                }
              }
            }
            """, "전주 한옥마을");

        assertThat(images).singleElement().satisfies(image -> {
            assertThat(image.sourceImageId()).startsWith("gallery-1:");
            assertThat(image.sourceUrl())
                .isEqualTo("https://images.example.test/hanok.jpg");
            assertThat(image.sourceDigest())
                .isEqualTo(CloudPlaceJson.sha256("https://images.example.test/hanok.jpg"));
            assertThat(image.reusable()).isTrue();
        });
    }

    @Test
    void ignoresSearchResultsThatDoNotMatchThePlace() {
        assertThat(TourPhotoGalleryProvider.parseImages("""
            {
              "response": {
                "header": {"resultCode": "0000"},
                "body": {
                  "items": {
                    "item": {
                      "galContentId": "gallery-2",
                      "galTitle": "부산 해운대",
                      "galPhotographyLocation": "부산",
                      "galWebImageUrl": "https://images.example.test/haeundae.jpg"
                    }
                  }
                }
              }
            }
            """, "전주 한옥마을")).isEmpty();
    }

    @Test
    void rejectsTourApiErrors() {
        assertThatThrownBy(() -> TourPhotoGalleryProvider.parseImages("""
            {
              "response": {
                "header": {"resultCode": "03", "resultMsg": "NO_DATA"}
              }
            }
            """, "전주 한옥마을"))
            .isInstanceOf(CatalogRefreshProviderException.class)
            .hasMessage("provider_failure_03");
    }
}
