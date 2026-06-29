package com.eop.teams;

import com.eop.authz.AuthorizationService;
import com.eop.authz.CurrentPrincipal;
import com.eop.authz.Permission;
import com.eop.authz.Scope;
import com.eop.platform.ConflictException;
import com.eop.platform.NotFoundException;
import com.eop.platform.OutboxWriter;
import com.eop.platform.UnprocessableException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Team CRUD + membership (Phase 5c). Direct CRUD — NOT over the request engine (no approval/SoD/
 * provisioning). Read ABAC at this layer ({@code team.read}/{@code team.manage} {@code OWN} = creator OR
 * active member). Membership changes are soft-deletes that emit audit events to the shared outbox (Phase 6
 * consumes); v1 has no Graph/group backing (Option A — group reflection is the deferred Phase-6-relay
 * follow-on, and Entra-group creation would need a new broad consent).
 */
@Service
public class TeamService {

    private static final String AGG = "team";

    private final TeamRepository teams;
    private final TeamMemberRepository members;
    private final AuthorizationService authz;
    private final OutboxWriter outbox;
    private final ObjectMapper json;

    public TeamService(TeamRepository teams, TeamMemberRepository members, AuthorizationService authz,
            OutboxWriter outbox, ObjectMapper json) {
        this.teams = teams;
        this.members = members;
        this.authz = authz;
        this.outbox = outbox;
        this.json = json;
    }

    @Transactional
    public Team create(CurrentPrincipal principal, TeamCreate body) {
        authz.require(principal, Permission.TEAM_MANAGE); // create isn't resource-scoped — permission only
        if (!StringUtils.hasText(body.name())) {
            throw new UnprocessableException("name is required");
        }
        if (teams.findByName(body.name()).isPresent()) {
            throw new ConflictException("team name already exists: " + body.name());
        }
        TeamEntity t = new TeamEntity(UUID.randomUUID(), body.name(), body.description(), principal.realUserId());
        teams.save(t);
        emit("team.created", t.getId(), null, principal.realUserId());
        return new Team(t.getId().toString(), t.getName(), t.getDescription(), 0);
    }

    @Transactional(readOnly = true)
    public List<Team> list(CurrentPrincipal principal) {
        authz.require(principal, Permission.TEAM_READ);
        Scope scope = authz.scopeFor(principal.effectiveRoles(), Permission.TEAM_READ);
        List<TeamEntity> found = scope == Scope.OWN
                ? teams.findOwnedOrMember(principal.realUserId())
                : teams.findAllOrdered();
        if (found.isEmpty()) {
            return List.of();
        }
        Map<UUID, Integer> counts = memberCounts(found.stream().map(TeamEntity::getId).toList());
        return found.stream()
                .map(t -> new Team(t.getId().toString(), t.getName(), t.getDescription(),
                        counts.getOrDefault(t.getId(), 0)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TeamMember> members(CurrentPrincipal principal, UUID teamId) {
        TeamEntity team = loadWithMembers(teamId);                // 404
        authz.require(principal, Permission.TEAM_READ, team);     // 403 — OWN = creator or member
        return members.findActiveByTeamId(teamId).stream()
                .map(m -> new TeamMember(m.getUserId(), null, m.getAddedAt()))
                .toList();
    }

    @Transactional
    public TeamMember addMember(CurrentPrincipal principal, UUID teamId, TeamMemberAdd body) {
        if (!StringUtils.hasText(body.userId())) {
            throw new UnprocessableException("userId is required");
        }
        TeamEntity team = load(teamId);                           // 404 — NO members loaded
        authz.require(principal, Permission.TEAM_MANAGE, team);   // 403 — OWN = creator only (manage ≠ read)
        TeamMemberEntity m = members.findByTeamIdAndUserId(teamId, body.userId()).orElse(null);
        if (m == null) {
            m = new TeamMemberEntity(UUID.randomUUID(), teamId, body.userId());
            members.save(m);
            emit("team.member.added", teamId, body.userId(), principal.realUserId());
        } else if (m.getRemovedAt() != null) {
            m.reactivate();                                       // re-add reactivates the soft-deleted row
            members.save(m);
            emit("team.member.added", teamId, body.userId(), principal.realUserId());
        } // else already an active member → idempotent (no row change, no event)
        return new TeamMember(m.getUserId(), null, m.getAddedAt());
    }

    @Transactional
    public void removeMember(CurrentPrincipal principal, UUID teamId, String userId) {
        TeamEntity team = load(teamId);                           // 404 — NO members loaded
        authz.require(principal, Permission.TEAM_MANAGE, team);   // 403 — OWN = creator only (manage ≠ read)
        TeamMemberEntity m = members.findByTeamIdAndUserId(teamId, userId)
                .filter(x -> x.getRemovedAt() == null)
                .orElseThrow(() -> new NotFoundException("user " + userId + " is not an active member"));
        m.softDelete();
        members.save(m);
        emit("team.member.removed", teamId, userId, principal.realUserId());
    }

    // ---- internals ----

    /**
     * Load WITHOUT members → {@code teamMemberIds()} is empty, so {@code owns()} reduces to creator-only.
     * Used for {@code team.manage} (add/remove) so management is owner-only (members are read-only); the
     * shared {@code Ownable.owns()} (ownerId OR teamMemberIds) can't express manage≠read in one method, so
     * the distinction is which load the caller uses. ADMIN/SUPER are unaffected (ALL scope, no ABAC check).
     */
    private TeamEntity load(UUID teamId) {
        return teams.findById(teamId)
                .orElseThrow(() -> new NotFoundException("team " + teamId + " not found"));
    }

    private TeamEntity loadWithMembers(UUID teamId) {
        TeamEntity team = load(teamId);
        Set<String> active = members.findActiveByTeamId(teamId).stream()
                .map(TeamMemberEntity::getUserId)
                .collect(Collectors.toSet());
        team.withActiveMembers(active); // populated → team.read OWN = creator OR member
        return team;
    }

    private Map<UUID, Integer> memberCounts(List<UUID> teamIds) {
        Map<UUID, Integer> counts = new LinkedHashMap<>();
        for (Object[] row : members.countActiveByTeamIds(teamIds)) {
            counts.put((UUID) row[0], ((Number) row[1]).intValue());
        }
        return counts;
    }

    /** Audit event to the shared outbox (Pin C envelope: teamId/userId/actorId/occurredAt). Phase 6 relays. */
    private void emit(String eventType, UUID teamId, String userId, String actorId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("teamId", teamId.toString());
        payload.put("userId", userId);
        payload.put("actorId", actorId);
        payload.put("occurredAt", Instant.now().toString());
        outbox.append(AGG, teamId.toString(), eventType, write(payload));
    }

    private String write(Map<String, Object> payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize team event payload", e);
        }
    }
}
