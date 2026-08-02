package io.hortora.trellis.garden;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AdaptiveSearchResponse(
        List<GardenSearchResult> results,
        int requestedLimit,
        int availableAboveFloor,
        boolean extended,
        boolean trimmed,
        int floorFiltered,
        boolean collectionReady) {}
