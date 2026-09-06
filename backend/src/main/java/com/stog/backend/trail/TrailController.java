package com.stog.backend.trail;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/trails")
public class TrailController {
    private final GoogleRoutesClient routes;

    public TrailController(GoogleRoutesClient routes) {
        this.routes = routes;
    }

    @PostMapping("/compute")
    public TrailResult compute(
        @Valid @RequestBody TrailRequests.Compute request
    ) {
        return routes.compute(request);
    }
}
