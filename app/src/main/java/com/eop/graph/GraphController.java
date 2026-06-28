package com.eop.graph;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Flow 2c — call real Microsoft Graph as the app (app-only).
 *
 * <p>{@code GET /api/graph/groups} (active session required) will trigger 3a→3c: mint the workload
 * JWT, exchange it at Entra via WIF, then {@code GET /v1.0/groups?$select=id,displayName&$top=20},
 * following {@code @odata.nextLink} and honoring {@code 429 Retry-After}. Implemented in Phase 5.
 */
@RestController
@RequestMapping("/api/graph")
public class GraphController {

    @GetMapping("/groups")
    public ResponseEntity<Map<String, Object>> groups() {
        return ResponseEntity
                .status(HttpStatus.NOT_IMPLEMENTED)
                .body(Map.of("error", "not_implemented", "flow", "Flow 2 (Graph via WIF) — implemented in Phase 5"));
    }
}
