package com.eop.access;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccessGrantRepository extends JpaRepository<AccessGrantEntity, UUID> {

    /** Idempotency: a grant row already written for this request? */
    Optional<AccessGrantEntity> findByRequestId(UUID requestId);

    /** The currently-held grant for a (user, resource), if any — removal target + dup-grant guard. */
    @Query("""
            SELECT g FROM AccessGrantEntity g
             WHERE g.userId = :userId AND g.resourceId = :resourceId AND g.removedAt IS NULL
            """)
    Optional<AccessGrantEntity> findActive(@Param("userId") String userId, @Param("resourceId") String resourceId);

    /** my-access: everything the user currently holds. */
    @Query("""
            SELECT g FROM AccessGrantEntity g
             WHERE g.userId = :userId AND g.removedAt IS NULL
             ORDER BY g.grantedAt DESC
            """)
    List<AccessGrantEntity> findActiveByUser(@Param("userId") String userId);

    /** Removal completion: mark the active grant removed (idempotent — no-op if already removed). */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE AccessGrantEntity g SET g.removedAt = :removedAt
             WHERE g.userId = :userId AND g.resourceId = :resourceId AND g.removedAt IS NULL
            """)
    int markRemoved(@Param("userId") String userId, @Param("resourceId") String resourceId,
            @Param("removedAt") Instant removedAt);
}
