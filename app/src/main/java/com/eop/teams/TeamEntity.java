package com.eop.teams;

import com.eop.authz.Ownable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * A team (Phase 5c). Implements {@link Ownable} so {@code team.read}/{@code team.manage} {@code OWN} scope
 * resolves to <b>creator OR active member</b> — closing the "a member can't see their own team" gap. The
 * active member oids are a transient field the service loads alongside the entity (there's no JPA
 * relationship — soft-delete filtering lives in the repository). Implements {@link Persistable} so the
 * client-assigned UUID still INSERTs.
 *
 * <p>NB (Pin A): team co-ownership is scoped to {@code TeamEntity} in v1. Onboarding {@code Application}s do
 * NOT get team co-ownership yet — {@code Application.team[]} sits in the request payload and
 * {@code RequestEntity} deliberately keeps the empty {@code teamMemberIds()} default (no payload parsing in
 * the engine). Wiring app co-ownership is the deferred {@code TeamMembershipResolver} port (Pin B).
 */
@Entity
@Table(schema = "teams", name = "teams")
public class TeamEntity implements Ownable, Persistable<UUID> {

    @Id
    private UUID id;

    private String name;
    private String description;
    private String owner;

    @Column(name = "created_at")
    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    /** Active member oids, loaded by the service before an ABAC check; empty until populated. */
    @Transient
    private Set<String> activeMemberIds = Set.of();

    protected TeamEntity() {
    }

    public TeamEntity(UUID id, String name, String description, String owner) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.owner = owner;
        this.createdAt = Instant.now();
        this.isNew = true;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    // --- ABAC ---

    @Override
    public String ownerId() {
        return owner;
    }

    @Override
    public Set<String> teamMemberIds() {
        return activeMemberIds;
    }

    public void withActiveMembers(Set<String> memberIds) {
        this.activeMemberIds = memberIds == null ? Set.of() : memberIds;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getOwner() {
        return owner;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
