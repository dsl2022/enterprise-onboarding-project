package com.eop.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import com.eop.TestcontainersConfig;
import com.eop.authz.CurrentPrincipal;
import com.eop.authz.ForbiddenException;
import com.eop.authz.PortalRole;
import com.eop.platform.ConflictException;
import com.eop.platform.OutboxWriter;
import com.eop.platform.PreconditionFailedException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The request engine against real Postgres: full lifecycle (both types), illegal transitions, optimistic
 * concurrency, separation of duties (incl. the impersonation laundering guard), the authorize-before-state
 * ordering, the concurrent-approver serializer, and the non-transition failure event.
 */
@SpringBootTest
@ActiveProfiles("data")
@Import(TestcontainersConfig.class)
class RequestEngineTest {

    @Autowired
    RequestService service;

    @Autowired
    RequestEventRepository eventRepo;

    @Autowired
    JdbcTemplate jdbc;

    // Real bean by default (other tests' outbox counts stay accurate); Spring resets it after each test.
    @SpyBean
    OutboxWriter outboxSpy;

    private CurrentPrincipal principal(String userId, Set<PortalRole> roles, PortalRole impersonated) {
        return new CurrentPrincipal(userId, "User " + userId, userId + "@eop", roles, impersonated);
    }

    private long outboxCount(UUID id) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM messaging.outbox WHERE aggregate_id = ?", Long.class, id.toString());
    }

    // ---- full lifecycle ----

    @Test
    void onboarding_lifecycle_to_active_persists_external_ref_and_timeline() {
        var owner = principal("owner-1", Set.of(PortalRole.APPLICATION_OWNER), null);
        var ops = principal("ops-1", Set.of(PortalRole.SSO_OPERATIONS), null);

        var req = service.create(RequestType.ONBOARDING, "owner-1", "owner-1", "{\"name\":\"app\"}");
        assertThat(req.getStatus()).isEqualTo(RequestStatus.DRAFT);

        var underReview = service.submit(owner, req.getId(), req.getVersion());
        assertThat(underReview.getStatus()).isEqualTo(RequestStatus.UNDER_REVIEW);

        var approved = service.decide(ops, req.getId(), Decision.APPROVE, "lgtm", underReview.getVersion());
        assertThat(approved.getStatus()).isEqualTo(RequestStatus.APPROVED);
        assertThat(approved.getApprover()).isEqualTo("ops-1");

        assertThat(service.markProvisioning(req.getId(), Instant.now().plusSeconds(300)).getStatus())
                .isEqualTo(RequestStatus.PROVISIONING);
        var active = service.markProvisioned(req.getId(), "client-xyz");
        assertThat(active.getStatus()).isEqualTo(RequestStatus.ACTIVE);
        assertThat(active.getExternalRef()).isEqualTo("client-xyz");

        // Timeline records the SUBMITTED hop on the way to UNDER_REVIEW (per the auto-advance decision).
        List<String> events = service.timeline(req.getId()).stream()
                .map(RequestEventEntity::getEventType).toList();
        assertThat(events).containsExactly("CREATED", "SUBMITTED", "AUTO_UNDER_REVIEW",
                "DECISION_APPROVE", "PROVISIONING", "ACTIVE");
        assertThat(outboxCount(req.getId())).isEqualTo(events.size());
    }

    @Test
    void access_request_is_born_in_review_and_reaches_granted() {
        var ops = principal("ops-1", Set.of(PortalRole.SSO_OPERATIONS), null);
        var req = service.create(RequestType.ACCESS, "user-9", "user-9", "{\"resourceId\":\"aws\"}");
        assertThat(req.getStatus()).isEqualTo(RequestStatus.UNDER_REVIEW); // auto-advanced (no separate submit)

        service.decide(ops, req.getId(), Decision.APPROVE, "ok", req.getVersion());
        service.markProvisioning(req.getId(), Instant.now().plusSeconds(300));
        assertThat(service.markProvisioned(req.getId(), null).getStatus()).isEqualTo(RequestStatus.GRANTED);
    }

    // ---- illegal transition / no partial side effects ----

    @Test
    void decision_on_a_draft_is_409_and_leaves_no_side_effects() {
        var ops = principal("ops-1", Set.of(PortalRole.SSO_OPERATIONS), null);
        var req = service.create(RequestType.ONBOARDING, "owner-1", "owner-1", "{}"); // DRAFT

        assertThatThrownBy(() -> service.decide(ops, req.getId(), Decision.APPROVE, "x", null))
                .isInstanceOf(ConflictException.class);

        assertThat(service.get(req.getId()).getStatus()).isEqualTo(RequestStatus.DRAFT);
        // Only the CREATED event/outbox row exist — the rejected transition wrote nothing.
        assertThat(service.timeline(req.getId())).extracting(RequestEventEntity::getEventType)
                .containsExactly("CREATED");
        assertThat(outboxCount(req.getId())).isEqualTo(1);
    }

    // ---- optimistic concurrency ----

    @Test
    void stale_if_match_is_412() {
        var owner = principal("owner-1", Set.of(PortalRole.APPLICATION_OWNER), null);
        var ops = principal("ops-1", Set.of(PortalRole.SSO_OPERATIONS), null);
        var req = service.create(RequestType.ONBOARDING, "owner-1", "owner-1", "{}");
        var underReview = service.submit(owner, req.getId(), null);

        assertThatThrownBy(() -> service.decide(ops, req.getId(), Decision.APPROVE, "x", underReview.getVersion() - 1))
                .isInstanceOf(PreconditionFailedException.class);
    }

    // ---- separation of duties + laundering ----

    @Test
    void requester_cannot_approve_own_request() {
        // admin is both requester and an approver-capable role → SoD blocks the self-approval.
        var admin = principal("admin-1", Set.of(PortalRole.ADMIN), null);
        var req = service.create(RequestType.ONBOARDING, "admin-1", "admin-1", "{}");
        service.submit(admin, req.getId(), null);

        assertThatThrownBy(() -> service.decide(admin, req.getId(), Decision.APPROVE, "self", null))
                .isInstanceOf(ForbiddenException.class)
                .extracting(e -> ((ForbiddenException) e).reason())
                .isEqualTo(ForbiddenException.Reason.SEPARATION_OF_DUTIES);
    }

    @Test
    void impersonation_cannot_launder_self_approval() {
        // Super Admin submits as self, impersonates SSO_OPERATIONS (which can decide), approves own request.
        var superReal = principal("super-1", Set.of(PortalRole.SUPER_ADMIN), null);
        var req = service.create(RequestType.ONBOARDING, "super-1", "super-1", "{}");
        service.submit(superReal, req.getId(), null);

        var impersonating = principal("super-1", Set.of(PortalRole.SUPER_ADMIN), PortalRole.SSO_OPERATIONS);
        assertThatThrownBy(() -> service.decide(impersonating, req.getId(), Decision.APPROVE, "laundered", null))
                .isInstanceOf(ForbiddenException.class)
                .extracting(e -> ((ForbiddenException) e).reason())
                .isEqualTo(ForbiddenException.Reason.SEPARATION_OF_DUTIES);
    }

    // ---- authorize BEFORE revealing state ----

    @Test
    void unauthorized_caller_gets_403_not_state_leakage() {
        // Auditor lacks app.decide. Even with a stale If-Match AND an illegal-from state (DRAFT), the
        // caller must get 403 — never 412/409 — so staleness/decidability isn't leaked.
        var auditor = principal("auditor-1", Set.of(PortalRole.AUDITOR), null);
        var req = service.create(RequestType.ONBOARDING, "owner-1", "owner-1", "{}"); // DRAFT

        assertThatThrownBy(() -> service.decide(auditor, req.getId(), Decision.APPROVE, "x", 999))
                .isInstanceOf(ForbiddenException.class)
                .extracting(e -> ((ForbiddenException) e).reason())
                .isEqualTo(ForbiddenException.Reason.PERMISSION);
    }

    // ---- concurrent approvers: the guarded UPDATE is the serializer ----

    @Test
    void two_approvers_one_wins_one_conflict() throws Exception {
        var owner = principal("owner-1", Set.of(PortalRole.APPLICATION_OWNER), null);
        var opsA = principal("ops-a", Set.of(PortalRole.SSO_OPERATIONS), null);
        var opsB = principal("ops-b", Set.of(PortalRole.SSO_OPERATIONS), null);
        var req = service.create(RequestType.ONBOARDING, "owner-1", "owner-1", "{}");
        var underReview = service.submit(owner, req.getId(), null);
        int version = underReview.getVersion();
        UUID id = req.getId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Object> a = () -> attempt(() -> service.decide(opsA, id, Decision.APPROVE, "a", version));
            Callable<Object> b = () -> attempt(() -> service.decide(opsB, id, Decision.APPROVE, "b", version));
            Future<Object> fa = pool.submit(a);
            Future<Object> fb = pool.submit(b);
            Object ra = fa.get();
            Object rb = fb.get();

            long successes = List.of(ra, rb).stream().filter(r -> r instanceof RequestEntity).count();
            // The loser's exact failure depends on timing: ConflictException (409) once the winner's
            // status change is visible, or PreconditionFailedException (412) when only the version has
            // bumped yet. Both are valid "lost the optimistic-concurrency race" outcomes — accept either
            // (asserting only ConflictException is what made this test flaky).
            long lostRace = List.of(ra, rb).stream()
                    .filter(r -> r instanceof ConflictException || r instanceof PreconditionFailedException)
                    .count();
            assertThat(successes).isEqualTo(1);
            assertThat(lostRace).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(service.get(id).getStatus()).isEqualTo(RequestStatus.APPROVED);
    }

    /**
     * Run a transition, returning the result or the thrown <em>logical</em> exception (so both threads
     * complete). A {@link org.springframework.dao.TransientDataAccessException} (deadlock loser,
     * lock-acquisition timeout, serialization failure) is genuinely retryable infra contention — NOT a
     * logical outcome — so we retry it with a small backoff rather than let it masquerade as "nobody won"
     * (CR-20260629-1510). Non-transient results (ConflictException / PreconditionFailedException) are the
     * logical race outcomes and are returned, never retried — so the serializer invariant the test asserts
     * (exactly one winner, one conflict) is preserved, just no longer flaky under heavy parallel CI load.
     */
    private Object attempt(Callable<RequestEntity> action) {
        for (int tries = 0; ; tries++) {
            try {
                return action.call();
            } catch (org.springframework.dao.TransientDataAccessException transientEx) {
                if (tries >= 10) {
                    return transientEx; // give up — let the assertion surface a genuinely stuck serializer
                }
                try {
                    Thread.sleep(10L * (tries + 1)); // brief backoff, then re-drive to its deterministic end
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return ie;
                }
            } catch (Exception e) {
                return e; // a logical outcome (Conflict / PreconditionFailed / etc.)
            }
        }
    }

    // ---- non-transition event (rescoped invariant) ----

    @Test
    void provisioning_failed_records_an_event_without_changing_state() {
        var ops = principal("ops-1", Set.of(PortalRole.SSO_OPERATIONS), null);
        var req = service.create(RequestType.ACCESS, "user-9", "user-9", "{}");
        service.decide(ops, req.getId(), Decision.APPROVE, "ok", req.getVersion());
        service.markProvisioning(req.getId(), Instant.now().plusSeconds(300));

        service.provisioningFailed(req.getId(), "Graph 403: consent missing");

        assertThat(service.get(req.getId()).getStatus()).isEqualTo(RequestStatus.PROVISIONING); // unchanged
        assertThat(service.timeline(req.getId())).extracting(RequestEventEntity::getEventType)
                .contains("PROVISIONING_FAILED");
    }

    // ---- transactional integrity: a failure mid-advance rolls back the WHOLE transition ----

    @Test
    void failure_after_the_guarded_update_rolls_back_status_event_and_outbox() {
        var owner = principal("owner-1", Set.of(PortalRole.APPLICATION_OWNER), null);
        var ops = principal("ops-1", Set.of(PortalRole.SSO_OPERATIONS), null);
        var req = service.create(RequestType.ONBOARDING, "owner-1", "owner-1", "{}");
        var underReview = service.submit(owner, req.getId(), null);
        int versionBefore = underReview.getVersion();
        long outboxBefore = outboxCount(req.getId());

        // Fail the outbox append that happens AFTER the guarded UPDATE + the event insert, in the same tx.
        doThrow(new RuntimeException("boom")).when(outboxSpy)
                .append(any(), any(), eq("request.approved"), any());

        assertThatThrownBy(() -> service.decide(ops, req.getId(), Decision.APPROVE, "x", null))
                .isInstanceOf(RuntimeException.class);

        var after = service.get(req.getId());
        assertThat(after.getStatus()).isEqualTo(RequestStatus.UNDER_REVIEW); // UPDATE rolled back
        assertThat(after.getVersion()).isEqualTo(versionBefore);             // version unchanged
        assertThat(service.timeline(req.getId())).extracting(RequestEventEntity::getEventType)
                .doesNotContain("DECISION_APPROVE");                         // event insert rolled back
        assertThat(outboxCount(req.getId())).isEqualTo(outboxBefore);        // no outbox row leaked
    }
}
