package com.eop.teams;

import com.eop.authz.CurrentPrincipal;
import com.eop.platform.IdempotencyService;
import com.eop.platform.PrincipalFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Team endpoints (frozen contract): direct CRUD, no engine. Idempotency-Key wraps the creating POSTs;
 * member removal is a soft-delete (204). Read ABAC + the projection live in {@link TeamService}.
 */
@RestController
@RequestMapping("/api/v1")
public class TeamController {

    private final TeamService teamsService;
    private final IdempotencyService idempotency;
    private final PrincipalFactory principalFactory;
    private final ObjectMapper json;

    public TeamController(TeamService teamsService, IdempotencyService idempotency,
            PrincipalFactory principalFactory, ObjectMapper json) {
        this.teamsService = teamsService;
        this.idempotency = idempotency;
        this.principalFactory = principalFactory;
        this.json = json;
    }

    @GetMapping("/teams")
    public List<Team> list(@AuthenticationPrincipal OidcUser oidc, HttpSession session) {
        return teamsService.list(principal(oidc, session));
    }

    @PostMapping("/teams")
    public ResponseEntity<String> create(@AuthenticationPrincipal OidcUser oidc, HttpSession session,
            @RequestHeader("Idempotency-Key") String key, @RequestBody TeamCreate body) {
        CurrentPrincipal principal = principal(oidc, session);
        return idempotency.execute(principal.realUserId(), "POST /teams", key, hash(body),
                () -> new IdempotencyService.Outcome(201, null, teamsService.create(principal, body)));
    }

    @GetMapping("/teams/{id}/members")
    public List<TeamMember> members(@AuthenticationPrincipal OidcUser oidc, HttpSession session,
            @PathVariable UUID id) {
        return teamsService.members(principal(oidc, session), id);
    }

    @PostMapping("/teams/{id}/members")
    public ResponseEntity<String> addMember(@AuthenticationPrincipal OidcUser oidc, HttpSession session,
            @PathVariable UUID id, @RequestHeader("Idempotency-Key") String key, @RequestBody TeamMemberAdd body) {
        CurrentPrincipal principal = principal(oidc, session);
        return idempotency.execute(principal.realUserId(), "POST /teams/" + id + "/members", key, hash(body),
                () -> new IdempotencyService.Outcome(201, null, teamsService.addMember(principal, id, body)));
    }

    @DeleteMapping("/teams/{id}/members/{userId}")
    public ResponseEntity<Void> removeMember(@AuthenticationPrincipal OidcUser oidc, HttpSession session,
            @PathVariable UUID id, @PathVariable String userId) {
        teamsService.removeMember(principal(oidc, session), id, userId);
        return ResponseEntity.noContent().build();
    }

    // ---- helpers ----

    private CurrentPrincipal principal(OidcUser oidc, HttpSession session) {
        if (oidc == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return principalFactory.from(oidc, session);
    }

    private String hash(Object body) {
        try {
            return json.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            return "";
        }
    }
}
