package com.eop.notify;

import com.eop.authz.AuthorizationService;
import com.eop.authz.CurrentPrincipal;
import com.eop.authz.Permission;
import com.eop.platform.CursorCodec;
import com.eop.platform.NotFoundException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The caller's own notification feed + read-state (write side is {@link NotifyProjector}). Everything is
 * scoped to the <b>real</b> principal ({@code realUserId}), never the effective identity — a Super Admin
 * impersonating sees and marks THEIR own feed, not the impersonated user's (same ABAC-on-the-real-principal
 * rule as audit/SoD). {@code notifications.read} is held by every role, so the gate is effectively
 * "authenticated + own feed only".
 */
@Service
public class NotificationService {

    private static final int MAX_LIMIT = 100;
    private static final int DEFAULT_LIMIT = 20;

    private final JdbcTemplate jdbc;
    private final AuthorizationService authz;

    public NotificationService(JdbcTemplate jdbc, AuthorizationService authz) {
        this.jdbc = jdbc;
        this.authz = authz;
    }

    @Transactional(readOnly = true)
    public NotificationFeed feed(CurrentPrincipal principal, String cursor, int limit) {
        authz.require(principal, Permission.NOTIFICATIONS_READ);
        String uid = principal.realUserId(); // real principal — NOT the impersonated identity
        int page = CursorCodec.toPage(cursor);
        int lim = Math.min(limit <= 0 ? DEFAULT_LIMIT : limit, MAX_LIMIT);

        List<Notification> rows = jdbc.query(
                "SELECT id, type, title, body, resource_ref, read, created_at FROM notify.notifications "
                        + "WHERE recipient = ? ORDER BY created_at DESC, id DESC OFFSET ? LIMIT ?",
                MAPPER, uid, (long) page * lim, lim + 1);
        boolean hasNext = rows.size() > lim;
        List<Notification> items = hasNext ? rows.subList(0, lim) : rows;
        int unread = jdbc.queryForObject(
                "SELECT count(*) FROM notify.notifications WHERE recipient = ? AND read = false", Integer.class, uid);
        return new NotificationFeed(List.copyOf(items), unread, CursorCodec.nextCursor(page, hasNext));
    }

    @Transactional
    public void markRead(CurrentPrincipal principal, UUID id) {
        authz.require(principal, Permission.NOTIFICATIONS_READ);
        int n = jdbc.update("UPDATE notify.notifications SET read = true WHERE id = ? AND recipient = ?",
                id, principal.realUserId());
        if (n == 0) {
            // not the caller's notification (or absent) — 404, never leak another user's notification's existence
            throw new NotFoundException("notification " + id + " not found");
        }
    }

    @Transactional
    public void markAllRead(CurrentPrincipal principal) {
        authz.require(principal, Permission.NOTIFICATIONS_READ);
        jdbc.update("UPDATE notify.notifications SET read = true WHERE recipient = ? AND read = false",
                principal.realUserId());
    }

    private static final RowMapper<Notification> MAPPER = (rs, n) -> new Notification(
            rs.getString("id"), rs.getString("type"), rs.getString("title"), rs.getString("body"),
            rs.getString("resource_ref"), rs.getBoolean("read"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());
}
