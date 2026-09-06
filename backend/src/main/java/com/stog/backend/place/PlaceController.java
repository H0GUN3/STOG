package com.stog.backend.place;

import com.stog.backend.place.search.PlaceSearchResponse;
import com.stog.backend.place.search.PlaceSearchService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!cloud")
@RequestMapping("/places")
public class PlaceController {
    private final GooglePlacesClient places;
    private final PlaceSearchService search;

    public PlaceController(
        GooglePlacesClient places,
        PlaceSearchService search
    ) {
        this.places = places;
        this.search = search;
    }

    @PostMapping("/search")
    public PlaceSearchResponse search(@Valid @RequestBody PlaceRequests.Search request) {
        return search.search(request);
    }

    @PostMapping("/nearby")
    public PlaceSearchResponse nearby(@Valid @RequestBody PlaceRequests.Nearby request) {
        return search.nearby(request);
    }

    @GetMapping("/{placeId}")
    public PlaceCandidate details(@PathVariable String placeId) {
        return places.details(placeId);
    }

    @GetMapping("/photo")
    public ResponseEntity<byte[]> photo(@RequestParam String name) {
        GooglePlacesClient.PhotoResponse photo = places.photo(name);
        return ResponseEntity
            .ok()
            .contentType(photo.contentType())
            .body(photo.bytes());
    }

}
