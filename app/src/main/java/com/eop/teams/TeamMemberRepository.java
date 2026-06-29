package com.eop.teams;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TeamMemberRepository extends JpaRepository<TeamMemberEntity, UUID> {

    /** Active members of one team — members list, single-team memberCount, and ABAC teamMemberIds. */
    @Query("""
            SELECT m FROM TeamMemberEntity m
             WHERE m.teamId = :teamId AND m.removedAt IS NULL
             ORDER BY m.addedAt
            """)
    List<TeamMemberEntity> findActiveByTeamId(@Param("teamId") UUID teamId);

    /** The single (team,user) row regardless of state — add reactivates it; ≤1 since re-add reactivates. */
    Optional<TeamMemberEntity> findByTeamIdAndUserId(UUID teamId, String userId);

    /** Active-member counts grouped by team — single aggregate for GET /teams (no N+1). */
    @Query("""
            SELECT m.teamId, COUNT(m) FROM TeamMemberEntity m
             WHERE m.removedAt IS NULL AND m.teamId IN :teamIds
             GROUP BY m.teamId
            """)
    List<Object[]> countActiveByTeamIds(@Param("teamIds") List<UUID> teamIds);
}
