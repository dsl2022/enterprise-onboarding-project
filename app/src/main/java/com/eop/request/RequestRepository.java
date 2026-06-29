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
