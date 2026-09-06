package com.stog.backend.event;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local-import & event-refresh")
public class EventRefreshJob implements ApplicationRunner {
    private final TourApiFestivalProvider provider;
    private final EventRepository repository;
    private final TourApiEventProperties properties;
    private final Clock clock;

    @Autowired
    public EventRefreshJob(
        TourApiFestivalProvider provider,
        EventRepository repository,
        TourApiEventProperties properties
    ) {
        this(provider, repository, properties, Clock.systemUTC());
    }

    EventRefreshJob(
        TourApiFestivalProvider provider,
        EventRepository repository,
        TourApiEventProperties properties,
        Clock clock
    ) {
        this.provider = provider;
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        LocalDate from = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate to = from.plusDays(properties.horizonDays());
        provider.fetch(from, to).forEach(repository::upsert);
        repository.retireExpired(from);
    }
}
