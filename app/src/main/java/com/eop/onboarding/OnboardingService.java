package com.eop.onboarding;

import com.eop.authz.AuthorizationService;
import com.eop.authz.CurrentPrincipal;
import com.eop.authz.Permission;
import com.eop.authz.Scope;
import com.eop.platform.CursorCodec;
import com.eop.platform.UnprocessableException;
import com.eop.request.RequestEntity;
import com.eop.request.RequestEventEntity;
import com.eop.request.RequestService;
import com.eop.request.RequestStatus;
import com.eop.request.RequestType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * The onboarding application surface over the request engine. Owns the {@code payload}↔{@link Application}
 * projection (incl. the frozen {@code uris}→{@code redirectUris} rename and the total
 * RequestStatus→OnboardingStatus mapping) and enforces <b>read ABAC</b> at this layer — the engine's
 * reads are unguarded. Mutations delegate to the engine, which owns the authorize-before-state ordering.
 */
@Service
public class OnboardingService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final RequestService engine;
    private final AuthorizationService authz;
    private final ObjectMapper json;

    public OnboardingService(RequestService engine, AuthorizationService authz, ObjectMapper json) {
        this.engine = engine;
        this.authz = authz;
        this.json = json;
    }

    public ApplicationView create(CurrentPrincipal principal, ApplicationCreate body) {
        authz.require(principal, Permission.APP_CREATE);
        if (!StringUtils.hasText(body.name()) || !StringUtils.hasText(body.env())) {
            throw new UnprocessableException("name and env are required");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", body.name());
        payload.put("env", body.env());
        payload.put("description", body.description());
        payload.put("grants", orEmpty(body.grants()));
        payload.put("scopes", orEmpty(body.scopes()));
        payload.put("uris", orEmpty(body.uris()));
        payload.put("group", body.group());
        payload.put("team", orEmpty(body.team()));
        RequestEntity created = engine.create(RequestType.ONBOARDING, principal.realUserId(),
                principal.realUserId(), write(payload));
        return view(created);
    }

    public ApplicationView get(CurrentPrincipal principal, UUID id) {
        RequestEntity entity = engine.get(id);                 // 404
        authz.require(principal, Permission.APP_READ, entity); // 403 (ABAC ownership) before returning
        return view(entity);
    }

    public ApplicationPage list(CurrentPrincipal principal, String status, String owner, String cursor, int limit) {
        authz.require(principal, Permission.APP_READ); // 403 if no role grants app.read at all
        Scope scope = authz.scopeFor(principal.effectiveRoles(), Permission.APP_READ);
        String requesterFilter = scope == Scope.OWN ? principal.realUserId() : trimToNull(owner);
        int page = CursorCodec.toPage(cursor);
        int size = clampLimit(limit);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<RequestEntity> result = engine.list(RequestType.ONBOARDING, requesterFilter, parseStatus(status), pageable);
        List<Application> items = result.getContent().stream().map(this::project).toList();
        return new ApplicationPage(items, CursorCodec.nextCursor(page, result.hasNext()));
    }

    public ApplicationView patch(CurrentPrincipal principal, UUID id, ApplicationPatch patch, Integer ifMatchVersion) {
        RequestEntity current = engine.get(id);                          // 404 (read for merge)
        String merged = mergePayload(current.getPayload(), patch);       // merge, never replace
        RequestEntity updated = engine.updatePayload(principal, id, merged, ifMatchVersion); // authz→412→409→guard
        return view(updated);
    }

    public ApplicationView submit(CurrentPrincipal principal, UUID id, Integer ifMatchVersion) {
        return view(engine.submit(principal, id, ifMatchVersion));
    }

    public ApplicationView decide(CurrentPrincipal principal, UUID id, DecisionRequest body, Integer ifMatchVersion) {
        return view(engine.decide(principal, id, body.decision(), body.reason(), ifMatchVersion));
    }

    public List<TimelineEntry> timeline(CurrentPrincipal principal, UUID id) {
        RequestEntity entity = engine.get(id);                 // 404
        authz.require(principal, Permission.APP_READ, entity); // 403 before returning
        return engine.timeline(id).stream().map(this::toTimelineEntry).toList();
    }

    // ---- projection ----

    private ApplicationView view(RequestEntity entity) {
        return new ApplicationView(project(entity), entity.getVersion());
    }

    @SuppressWarnings("unchecked")
    private Application project(RequestEntity entity) {
        Map<String, Object> p = read(entity.getPayload());
        return new Application(
                entity.getId().toString(),
                (String) p.get("name"),
                (String) p.get("env"),
                (String) p.get("description"),
                entity.getStatus().name(), // RequestStatus → OnboardingStatus (onboarding never reaches GRANTED)
                entity.getRequester(),
                (List<String>) p.getOrDefault("team", List.of()),
                (List<String>) p.getOrDefault("grants", List.of()),
                (List<String>) p.getOrDefault("scopes", List.of()),
                (List<String>) p.getOrDefault("uris", List.of()), // uris → redirectUris
                (String) p.get("group"),
                entity.getExternalRef(), // clientId, set after provisioning
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private String mergePayload(String existingJson, ApplicationPatch patch) {
        Map<String, Object> p = read(existingJson);
        if (patch.description() != null) {
            p.put("description", patch.description());
        }
        if (patch.grants() != null) {
            p.put("grants", patch.grants());
        }
        if (patch.scopes() != null) {
            p.put("scopes", patch.scopes());
        }
        if (patch.uris() != null) {
            p.put("uris", patch.uris());
        }
        if (patch.group() != null) {
            p.put("group", patch.group());
        }
        if (patch.team() != null) {
            p.put("team", patch.team());
        }
        // name/env are intentionally never merged → immutable after create.
        return write(p);
    }

    private TimelineEntry toTimelineEntry(RequestEventEntity ev) {
        String status = ev.getToStatus() != null ? ev.getToStatus().name() : ev.getEventType();
        return new TimelineEntry(ev.getId().toString(), status, ev.getActor(), ev.getReason(), ev.getAt());
    }

    // ---- helpers ----

    private RequestStatus parseStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        try {
            return RequestStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new UnprocessableException("invalid status filter: " + status);
        }
    }

    private int clampLimit(int limit) {
        if (limit < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private List<String> orEmpty(List<String> v) {
        return v == null ? new ArrayList<>() : v;
    }

    private String trimToNull(String s) {
        return StringUtils.hasText(s) ? s : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String jsonString) {
        try {
            return jsonString == null || jsonString.isBlank()
                    ? new LinkedHashMap<>()
                    : json.readValue(jsonString, Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt request payload", e);
        }
    }

    private String write(Map<String, Object> payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize payload", e);
        }
    }
}
