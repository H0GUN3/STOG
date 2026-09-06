package com.stog.backend.cell;

import com.stog.backend.plan.CurrentUserId;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/cells")
public class CellController {
    private final CellService cells;

    public CellController(CellService cells) {
        this.cells = cells;
    }

    @GetMapping
    public CellResponses.Page summaries(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam(required = false) Double swLat,
        @RequestParam(required = false) Double swLng,
        @RequestParam(required = false) Double neLat,
        @RequestParam(required = false) Double neLng,
        @RequestParam(required = false) String cursor,
        @RequestParam(required = false) Integer limit
    ) {
        return cells.summaries(
            viewerId(jwt), swLat, swLng, neLat, neLng, cursor, limit
        );
    }

    @GetMapping("/{cellId}")
    public CellResponses.Detail detail(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable String cellId
    ) {
        return cells.detail(viewerId(jwt), cellId);
    }

    @GetMapping("/{cellId}/photos")
    public CellResponses.PhotoPage photos(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable String cellId,
        @RequestParam(required = false) String cursor,
        @RequestParam(required = false) Integer limit,
        @RequestParam(required = false) String scope
    ) {
        return cells.photos(viewerId(jwt), cellId, cursor, limit, scope);
    }

    private Long viewerId(Jwt jwt) {
        return jwt == null ? null : CurrentUserId.from(jwt);
    }
}
