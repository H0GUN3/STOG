package com.stog.backend.event;

import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!cloud")
@RequestMapping("/events")
public class EventController {
    private final EventSearchService search;

    public EventController(EventSearchService search) {
        this.search = search;
    }

    @PostMapping("/nearby")
    public EventSearchResponse nearby(@Valid @RequestBody EventRequests.Nearby request) {
        return search.nearby(request);
    }
}
