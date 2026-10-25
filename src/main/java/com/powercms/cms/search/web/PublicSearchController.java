package com.powercms.cms.search.web;

import com.powercms.cms.search.PublicSearchService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/delivery")
class PublicSearchController {

    private final PublicSearchService searchService;

    PublicSearchController(PublicSearchService searchService) { this.searchService = searchService; }

    record PublicSearchResponse(List<PublicSearchService.SearchResult> results, int total) {}

    @GetMapping("/sites/{siteId}/search")
    PublicSearchResponse search(@PathVariable UUID siteId, @RequestParam(defaultValue = "") String q) {
        var results = searchService.search(siteId, q);
        return new PublicSearchResponse(results, results.size());
    }
}
