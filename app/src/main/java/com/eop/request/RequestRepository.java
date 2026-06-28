package com.eop.request;

import java.time.Instant;
import java.util.UUID;
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
}
