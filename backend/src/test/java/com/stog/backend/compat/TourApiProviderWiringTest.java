package com.stog.backend.compat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.stog.backend.auth.SocialClientConfiguration;
import com.stog.backend.event.TourApiEventProperties;
import com.stog.backend.event.TourApiFestivalProvider;
import com.stog.backend.google.GoogleClientConfiguration;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class TourApiProviderWiringTest {
    @Test
    void tourismProvidersStartWhenBothRestClientBuildersExist() {
        try (AnnotationConfigApplicationContext context =
            new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("catalog-refresh", "event-refresh");
            context.register(
                SocialClientConfiguration.class,
                GoogleClientConfiguration.class,
                TourApiPublicCatalogProvider.class,
                TourApiFestivalProvider.class
            );
            context.registerBean(
                TourApiCatalogProperties.class,
                () -> new TourApiCatalogProperties(
                    URI.create("https://catalog.example.test"),
                    "service-key",
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1)
                )
            );
            context.registerBean(
                TourApiEventProperties.class,
                () -> new TourApiEventProperties(
                    URI.create("https://events.example.test"),
                    "service-key",
                    List.of(1),
                    90,
                    100,
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1)
                )
            );

            assertThatCode(context::refresh).doesNotThrowAnyException();
            assertThat(context.getBean(TourApiPublicCatalogProvider.class)).isNotNull();
            assertThat(context.getBean(TourApiFestivalProvider.class)).isNotNull();
        }
    }
}
