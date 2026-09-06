package com.stog.backend.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

class CatalogImageUrlTest {
    @Test
    void signsStoredCatalogObjectKey() {
        String url = CatalogImageUrl.resolve(
            null,
            "catalog-images/v1/source-images/21",
            key -> URI.create("https://storage.example/" + key)
        );

        assertThat(url).isEqualTo(
            "https://storage.example/catalog-images/v1/source-images/21"
        );
    }

    @Test
    void keepsSourceUrlWhenCatalogObjectCannotBeSigned() {
        String url = CatalogImageUrl.resolve(
            "https://example.com/place.jpg",
            "catalog-images/v1/source-images/21",
            null
        );

        assertThat(url).isEqualTo("https://example.com/place.jpg");
    }
}
