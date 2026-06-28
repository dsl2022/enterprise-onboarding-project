package com.eop.graph;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Flow 2 proof. Requires an active session (see SecurityConfig). Triggers the WIF mint → Entra
 * exchange → Graph call and returns the groups. Returns 501 only when WIF is disabled (local/CI).
 */
@RestController
@RequestMapping("/api/graph")
public class GraphController {

    private final ObjectProvider<GraphService> graph;

    public GraphController(ObjectProvider<GraphService> graph) {
        this.graph = graph;
    }

    @GetMapping("/groups")
    public ResponseEntity<Map<String, Object>> groups() {
        GraphService svc = graph.getIfAvailable();
        if (svc == null) {
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                    .body(Map.of("error", "not_implemented", "reason", "wif.enabled=false"));
        }
        try {
            List<Map<String, Object>> groups = svc.listGroups();
            return ResponseEntity.ok(Map.of("count", groups.size(), "groups", groups));
        } catch (ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .body(Map.of("error", "graph_error", "message", e.getReason() != null ? e.getReason() : "Graph call failed"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "graph_error", "message", e.getMessage() != null ? e.getMessage() : "Graph call failed"));
        }
    }
}
