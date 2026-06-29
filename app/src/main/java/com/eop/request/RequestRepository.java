package com.eop.request;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RequestRepository extends JpaRepository<RequestEntity, UUID> {

    /**
     * The authoritative serializer. Succeeds for exactly one caller: the row must still be in
     * {@code :from} at {@code :expectedVersion}. {@code COALESCE} leaves approver/reason/external_ref
     * untouched when the parameter is null, so a transition only writes what it carries.
     * {@code clearAutomatically} drops the stale first-level cache so a follow-up read sees the new row.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE RequestEntity r
               SET r.status = :to,
                   r.version = r.version + 1,
                   r.updatedAt = :now,
                   r.approver = COALESCE(:approver, r.approver),
                   r.reason = COALESCE(:reason, r.reason),
                   r.externalRef = COALESCE(:externalRef, r.externalRef)
             WHERE r.id = :id AND r.status = :from AND r.version = :expectedVersion
            """)
    int guardedTransition(@Param("id") UUID id,
            @Param("from") RequestStatus from,
            @Param("to") RequestStatus to,
            @Param("expectedVersion") int expectedVersion,
            @Param("approver") String approver,
            @Param("reason") String reason,
            @Param("externalRef") String externalRef,
            @Param("now") Instant now);

    /** Guarded payload edit: status is unchanged (must still be :status) and version is bumped. */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE RequestEntity r
               SET r.payload = :payload, r.version = r.version + 1, r.updatedAt = :now
             WHERE r.id = :id AND r.status = :status AND r.version = :expectedVersion
            """)
    int guardedPayloadUpdate(@Param("id") UUID id,
            @Param("status") RequestStatus status,
            @Param("expectedVersion") int expectedVersion,
            @Param("payload") String payload,
            @Param("now") Instant now);

    /**
     * Arm the provisioning lease on a fresh APPROVED→PROVISIONING claim (4b): set next_attempt_at to
     * now+lease and reset the backoff counter. Touches only the reaper columns (no status/version change)
     * — it runs in the same transaction as the guarded claim, so it's atomic with it.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE RequestEntity r
               SET r.nextAttemptAt = :nextAttemptAt, r.provisionAttempts = 0
             WHERE r.id = :id
            """)
    int armProvisioningLease(@Param("id") UUID id, @Param("nextAttemptAt") Instant nextAttemptAt);

    /**
     * Guarded reaper re-claim (4b): one reaper wins a stale PROVISIONING row. Bumps version (so a second
     * reaper's stale-version re-claim fails → only one calls Graph), increments the backoff counter, and
     * pushes next_attempt_at out. Status stays PROVISIONING.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE RequestEntity r
               SET r.version = r.version + 1,
                   r.provisionAttempts = r.provisionAttempts + 1,
                   r.nextAttemptAt = :nextAttemptAt,
                   r.updatedAt = :now
             WHERE r.id = :id
               AND r.status = com.eop.request.RequestStatus.PROVISIONING
               AND r.version = :expectedVersion
            """)
    int reclaimProvisioning(@Param("id") UUID id,
            @Param("expectedVersion") int expectedVersion,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("now") Instant now);

    /**
     * Reaper scan: rows of one TYPE stuck in PROVISIONING whose lease/backoff has elapsed (NULL = due now).
     * Type-scoped so the onboarding worker never reaps an access row (it would try to create an app
     * registration for it) and vice versa.
     */
    @Query("""
            SELECT r FROM RequestEntity r
             WHERE r.type = :type
               AND r.status = com.eop.request.RequestStatus.PROVISIONING
               AND (r.nextAttemptAt IS NULL OR r.nextAttemptAt <= :now)
            """)
    Page<RequestEntity> findStaleProvisioning(@Param("type") RequestType type,
            @Param("now") Instant now, Pageable pageable);

    /** Role-scoped list: optional requester (ABAC own-scope) and optional status filters. */
    @Query("""
            SELECT r FROM RequestEntity r
             WHERE r.type = :type
               AND (:requester IS NULL OR r.requester = :requester)
               AND (:status IS NULL OR r.status = :status)
            """)
    Page<RequestEntity> listByType(@Param("type") RequestType type,
            @Param("requester") String requester,
            @Param("status") RequestStatus status,
            Pageable pageable);

    /** Unified review queue: everything UNDER_REVIEW, optionally filtered by type. */
    @Query("""
            SELECT r FROM RequestEntity r
             WHERE r.status = com.eop.request.RequestStatus.UNDER_REVIEW
               AND (:type IS NULL OR r.type = :type)
            """)
    Page<RequestEntity> findUnderReview(@Param("type") RequestType type, Pageable pageable);
}
