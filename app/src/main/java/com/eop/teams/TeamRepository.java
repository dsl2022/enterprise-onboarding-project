package com.eop.teams;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TeamRepository extends JpaRepository<TeamEntity, UUID> {

    /** Name is tenant-unique → 409 on a duplicate create. */
    Optional<TeamEntity> findByName(String name);

    /** {@code team.read(own)} list scope: teams the user created OR is an active member of. */
    @Query("""
            SELECT t FROM TeamEntity t
             WHERE t.owner = :userId
                OR t.id IN (SELECT m.teamId FROM TeamMemberEntity m
                             WHERE m.userId = :userId AND m.removedAt IS NULL)
             ORDER BY t.createdAt DESC
            """)
    List<TeamEntity> findOwnedOrMember(@Param("userId") String userId);

    @Query("SELECT t FROM TeamEntity t ORDER BY t.createdAt DESC")
    List<TeamEntity> findAllOrdered();
}
