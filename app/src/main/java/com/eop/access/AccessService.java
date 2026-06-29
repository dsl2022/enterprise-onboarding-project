package com.eop.access;

import com.eop.authz.AuthorizationService;
import com.eop.authz.CurrentPrincipal;
import com.eop.authz.Permission;
import com.eop.authz.Scope;
import com.eop.platform.CursorCodec;
import com.eop.platform.NotFoundException;
import com.eop.platform.UnprocessableException;
import com.eop.request.RequestEntity;
import com.eop.request.RequestService;
import com.eop.request.RequestStatus;
import com.eop.request.RequestType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * The access-governance surface over the request engine. Owns the {@code payload}↔{@link AccessRequest}
 * projection and enforces <b>read ABAC</b> here (engine reads are unguarded). Access requests reuse the
 * engine's {@code RequestType.ACCESS} (create auto-advances to UNDER_REVIEW — no separate submit; terminal
 * = GRANTED). Catalog is read-only reference data; my-access reads the {@code access_grant} projection
 * (the source of truth for "currently held", not request status).
 */
@Service
public class AccessService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final String KIND_GRANT = "grant";
    private static final String KIND_REMOVAL = "removal";

    private final RequestService engine;
    private final AuthorizationService authz;
    private final CatalogRepository catalog;
    private final AccessGrantRepository grants;
    private final ObjectMapper json;

    public AccessService(RequestService engine, AuthorizationService authz, CatalogRepository catalog,
            AccessGrantRepository grants, ObjectMapper json) {
        this.engine = engine;
        this.authz = authz;
        this.catalog = catalog;
        this.grants = grants;
        this.json = json;
    }

    // ---- catalog (read-only reference data) ----

    public CatalogPage listCatalog(CurrentPrincipal principal, String type, String risk, String cursor, int limit) {
        authz.require(principal, Permission.CATALOG_READ);
        int page = CursorCodec.toPage(cursor);
        Pageable pageable = PageRequest.of(page, clampLimit(limit), Sort.by("name"));
        Page<CatalogEntity> result = catalog.search(trimToNull(type), trimToNull(risk), pageable);
        List<CatalogResource> items = result.getContent().stream().map(this::catalogView).toList();
        return new CatalogPage(items, CursorCodec.nextCursor(page, result.hasNext()));
    }

    public CatalogResource getCatalog(CurrentPrincipal principal, String id) {
        authz.require(principal, Permission.CATALOG_READ);
        return catalogView(catalog.findById(id)
                .orElseThrow(() -> new NotFoundException("catalog resource " + id + " not found")));
    }

    // ---- access requests (over the engine) ----

    public AccessRequestView create(CurrentPrincipal principal, AccessRequestCreate body) {
        authz.require(principal, Permission.ACCESS_REQUEST);
        if (!StringUtils.hasText(body.resourceId()) || !StringUtils.hasText(body.justification())) {
            throw new UnprocessableException("resourceId and justification are required");
        }
        CatalogEntity resource = catalog.findById(body.resourceId())
                .orElseThrow(() -> new UnprocessableException("unknown catalog resource: " + body.resourceId()));
        // Reject a grant for a resource the user already holds (cleanest fix for the active-grant unique
        // index edge case — don't let a duplicate enter review only to fail at completion).
        if (grants.findActive(principal.realUserId(), body.resourceId()).isPresent()) {
            throw new UnprocessableException("you already hold access to " + body.resourceId());
        }
        Map<String, Object> payload = basePayload(resource, KIND_GRANT, body.justification(), body.duration());
        RequestEntity created = engine.create(RequestType.ACCESS, principal.realUserId(),
                principal.realUserId(), write(payload));
        return view(created);
    }

    public AccessRequestView requestRemoval(CurrentPrincipal principal, String resourceId) {
        authz.require(principal, Permission.MYACCESS_REMOVAL_REQUEST);
        grants.findActive(principal.realUserId(), resourceId)
                .orElseThrow(() -> new UnprocessableException("no active grant for resource " + resourceId));
        CatalogEntity resource = catalog.findById(resourceId)
                .orElseThrow(() -> new UnprocessableException("unknown catalog resource: " + resourceId));
        Map<String, Object> payload = basePayload(resource, KIND_REMOVAL, "removal requested", null);
        RequestEntity created = engine.create(RequestType.ACCESS, principal.realUserId(),
                principal.realUserId(), write(payload));
        return view(created);
    }

    public AccessRequestView decide(CurrentPrincipal principal, UUID id, DecisionRequest body, Integer ifMatchVersion) {
        return view(engine.decide(principal, id, body.decision(), body.reason(), ifMatchVersion));
    }

    public AccessRequestView get(CurrentPrincipal principal, UUID id) {
        RequestEntity entity = engine.get(id);                    // 404
        requireAccessType(entity);
        authz.require(principal, Permission.ACCESS_READ, entity); // 403 (ABAC ownership) before returning
        return view(entity);
    }

    public AccessRequestPage list(CurrentPrincipal principal, String status, String kind, String cursor, int limit) {
        authz.require(principal, Permission.ACCESS_READ);
        Scope scope = authz.scopeFor(principal.effectiveRoles(), Permission.ACCESS_READ);
        String requesterFilter = scope == Scope.OWN ? principal.realUserId() : null;
        int page = CursorCodec.toPage(cursor);
        Pageable pageable = PageRequest.of(page, clampLimit(limit), Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<RequestEntity> result = engine.list(RequestType.ACCESS, requesterFilter, parseStatus(status), pageable);
        String kindFilter = trimToNull(kind);
        // kind lives in the payload, not a column — filter the page in-memory (v1 dev scale; documented).
        List<AccessRequest> items = result.getContent().stream()
                .map(this::project)
                .filter(a -> kindFilter == null || kindFilter.equals(a.kind()))
                .toList();
        return new AccessRequestPage(items, CursorCodec.nextCursor(page, result.hasNext()));
    }

    // ---- my-access (the access_grant projection — source of truth for "currently held") ----

    public List<MyAccessItem> myAccess(CurrentPrincipal principal) {
        authz.require(principal, Permission.MYACCESS_READ);
        return grants.findActiveByUser(principal.realUserId()).stream()
                .map(g -> new MyAccessItem(g.getResourceId(), g.getResourceName(), g.getGrantedAt(),
                        g.getRequestId().toString()))
                .toList();
    }

    // ---- projection ----

    private AccessRequestView view(RequestEntity entity) {
        return new AccessRequestView(project(entity), entity.getVersion());
    }

    private AccessRequest project(RequestEntity entity) {
        Map<String, Object> p = read(entity.getPayload());
        return new AccessRequest(
                entity.getId().toString(),
                (String) p.get("resourceId"),
                (String) p.get("resourceName"),
                (String) p.getOrDefault("kind", KIND_GRANT),
                entity.getStatus().name(), // RequestStatus → AccessStatus (access reaches GRANTED)
                entity.getRequester(),
                (String) p.get("justification"),
                (String) p.get("duration"),
                entity.getApprover(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private CatalogResource catalogView(CatalogEntity c) {
        return new CatalogResource(c.getId(), c.getName(), c.getType(), c.getRisk(), c.getDescription(),
                c.getMappedGroup());
    }

    private Map<String, Object> basePayload(CatalogEntity resource, String kind, String justification, String duration) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resourceId", resource.getId());
        payload.put("resourceName", resource.getName());
        payload.put("kind", kind);
        payload.put("justification", justification);
        payload.put("duration", duration);
        payload.put("mappedGroup", resource.getMappedGroup());
        payload.put("risk", resource.getRisk());
        return payload;
    }

    // ---- helpers ----

    private void requireAccessType(RequestEntity entity) {
        if (entity.getType() != RequestType.ACCESS) {
            throw new NotFoundException("access request " + entity.getId() + " not found");
        }
    }

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
        return limit < 1 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
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
