package com.eop.review;

import com.eop.platform.CursorCodec;
import com.eop.request.RequestEntity;
import com.eop.request.RequestService;
import com.eop.request.RequestType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Projects the engine's UNDER_REVIEW requests (both types) into the unified review queue. Type-agnostic:
 * onboarding rows surface now; access rows appear automatically in Phase 5 since they share the table.
 * The title is derived generically from the payload ({@code name} for onboarding, {@code resourceName}
 * for access).
 */
@Service
public class ReviewService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final RequestService engine;
    private final ObjectMapper json;

    public ReviewService(RequestService engine, ObjectMapper json) {
        this.engine = engine;
        this.json = json;
    }

    public ReviewQueuePage queue(String type, String cursor, int limit) {
        RequestType typeFilter = parseType(type);
        int page = CursorCodec.toPage(cursor);
        Pageable pageable = PageRequest.of(page, clampLimit(limit), Sort.by(Sort.Direction.DESC, "updatedAt"));
        Page<RequestEntity> result = engine.underReview(typeFilter, pageable);
        List<ReviewItem> items = result.getContent().stream().map(this::project).toList();
        return new ReviewQueuePage(items, CursorCodec.nextCursor(page, result.hasNext()));
    }

    private ReviewItem project(RequestEntity r) {
        return new ReviewItem(
                r.getId().toString(),
                r.getType().name().toLowerCase(),
                title(r),
                r.getRequester(),
                r.getUpdatedAt());
    }

    private String title(RequestEntity r) {
        Map<String, Object> p = read(r.getPayload());
        Object name = p.getOrDefault("name", p.get("resourceName"));
        return name == null ? null : name.toString();
    }

    private RequestType parseType(String type) {
        if (!StringUtils.hasText(type)) {
            return null;
        }
        return "access".equalsIgnoreCase(type) ? RequestType.ACCESS : RequestType.ONBOARDING;
    }

    private int clampLimit(int limit) {
        return limit < 1 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String jsonString) {
        try {
            return jsonString == null || jsonString.isBlank() ? Map.of() : json.readValue(jsonString, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
