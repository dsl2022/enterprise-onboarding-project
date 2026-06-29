package com.eop.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eop.TestcontainersConfig;
import com.eop.authz.CurrentPrincipal;
import com.eop.authz.PortalRole;
import com.eop.platform.NotFoundException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The notification read/feed surface: feed + read-state scoped to the REAL principal (even while
 * impersonating), unreadCount, mark-read ABAC (you can't touch another user's), and read-all.
 */
@SpringBootTest
@ActiveProfiles("data")
@Import(TestcontainersConfig.class)
class NotificationEndpointTest {

    @Autowired NotificationService notifications;
    @Autowired JdbcTemplate jdbc;

    private CurrentPrincipal user(String realUserId, PortalRole impersonated) {
        return new CurrentPrincipal(realUserId, "U", realUserId + "@eop", Set.of(PortalRole.APPLICATION_OWNER), impersonated);
    }

    private UUID insert(String recipient, boolean read) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO notify.notifications "
                + "(id, source_event_id, recipient, type, title, body, resource_ref, read, created_at) "
                + "VALUES (?, ?, ?, 'request.approved', 'T', 'B', ?, ?, ?)",
                id, UUID.randomUUID(), recipient, "ref-" + id, read, OffsetDateTime.now(ZoneOffset.UTC));
        return id;
    }

    @Test
    void feed_is_scoped_to_the_real_principal_even_while_impersonating() {
        String me = "u-" + UUID.randomUUID();
        String other = "u-" + UUID.randomUUID();
        insert(me, false);
        insert(me, false);
        insert(other, false);

        // a Super Admin impersonating READ_ONLY still sees THEIR OWN feed (keyed on realUserId), not another's
        var principal = user(me, PortalRole.READ_ONLY);
        NotificationFeed feed = notifications.feed(principal, null, 20);
        assertThat(feed.items()).hasSize(2);
        assertThat(feed.items()).allSatisfy(n -> assertThat(n.read()).isFalse());
        assertThat(feed.unreadCount()).isEqualTo(2);
        // none of the other user's notifications leak in
        assertThat(notifications.feed(user(other, null), null, 20).items()).hasSize(1);
    }

    @Test
    void mark_read_is_owner_scoped_then_read_all_clears() {
        String me = "u-" + UUID.randomUUID();
        String stranger = "u-" + UUID.randomUUID();
        UUID mine = insert(me, false);
        insert(me, false);

        // a stranger cannot mark my notification read → 404 (don't leak existence)
        assertThatThrownBy(() -> notifications.markRead(user(stranger, null), mine))
                .isInstanceOf(NotFoundException.class);

        notifications.markRead(user(me, null), mine); // owner can
        assertThat(jdbc.queryForObject("SELECT read FROM notify.notifications WHERE id = ?", Boolean.class, mine)).isTrue();
        assertThat(notifications.feed(user(me, null), null, 20).unreadCount()).isEqualTo(1); // one still unread

        notifications.markAllRead(user(me, null));
        assertThat(notifications.feed(user(me, null), null, 20).unreadCount()).isZero();
    }
}
