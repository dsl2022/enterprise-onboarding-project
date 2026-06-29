package com.eop.notify;

import com.eop.platform.OutboxRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Projects a domain event into the in-app notification(s) for the people who care about it. Called ONLY by
 * {@link NotifyRelay} (deliberately NOT an {@code OutboxEventHandler} — that SPI feeds the single-leader
 * audit relay, and notify must stay a separate consumer so it can never stall the audit chain).
 *
 * <p>v1 recipient model: notify the individual already named in the event — the <b>requester</b> on a
 * decision/provisioning outcome, the <b>affected member</b> on a team change. No directory enumeration (the
 * "notify all reviewers of a new submission" fan-out needs role→user resolution and is deferred). Recipients
 * equal to the <b>actor</b> are suppressed — nobody is told about their own action. {@code createdAt} is the
 * event's occurred_at (causal feed order). Idempotent via {@code ON CONFLICT (source_event_id, recipient)}.
 */
@Component
public class NotifyProjector {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public NotifyProjector(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Runs inside {@link NotifyRelay}'s per-row transaction (claim → project → mark notified, all-or-nothing). */
    void project(OutboxRecord rec) {
        Content content = contentFor(rec.eventType());
        if (content == null) {
            return; // not a notifiable event (e.g. request.created/submitted/provisioning, team.created)
        }
        Map<String, Object> detail = parse(rec.payload());
        String recipient = recipientFor(rec.eventType(), detail);
        if (recipient == null || recipient.isBlank()) {
            return;
        }
        String actor = firstNonBlank(str(detail.get("actor")), str(detail.get("actorId")));
        if (recipient.equals(actor)) {
            return; // self-suppress — don't notify someone of their own action
        }
        jdbc.update("INSERT INTO notify.notifications "
                + "(id, source_event_id, recipient, type, title, body, resource_ref, read, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, false, ?) ON CONFLICT (source_event_id, recipient) DO NOTHING",
                UUID.randomUUID(), rec.id(), recipient, rec.eventType(), content.title(), content.body(),
                rec.aggregateId(), rec.occurredAt().atOffset(ZoneOffset.UTC));
    }

    /** The oid this event should notify, pulled straight from the payload (no directory lookup). */
    private String recipientFor(String eventType, Map<String, Object> detail) {
        return switch (eventType) {
            case "request.approved", "request.rejected", "request.changes_requested",
                    "request.active", "request.granted", "request.provisioning_failed" ->
                    str(detail.get("requester"));
            case "team.member.added", "team.member.removed" -> str(detail.get("userId"));
            default -> null;
        };
    }

    private record Content(String title, String body) {}

    private Content contentFor(String eventType) {
        return switch (eventType) {
            case "request.approved" -> new Content("Request approved", "Your request was approved.");
            case "request.rejected" -> new Content("Request rejected", "Your request was rejected.");
            case "request.changes_requested" ->
                    new Content("Changes requested", "Your request needs changes before it can proceed.");
            case "request.active" -> new Content("Application is ready", "Your onboarding request is now active.");
            case "request.granted" -> new Content("Access granted", "Your access request was granted.");
            case "request.provisioning_failed" ->
                    new Content("Provisioning issue", "We hit a problem provisioning your request; it will retry.");
            case "team.member.added" -> new Content("Added to a team", "You were added to a team.");
            case "team.member.removed" -> new Content("Removed from a team", "You were removed from a team.");
            default -> null;
        };
    }

    private Map<String, Object> parse(String payloadJson) {
        try {
            return json.readValue(payloadJson, MAP);
        } catch (Exception e) {
            throw new IllegalStateException("unparseable outbox payload", e);
        }
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
