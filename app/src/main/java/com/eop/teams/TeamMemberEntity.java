package com.eop.teams;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/** A team membership (Phase 5c). Soft-deleted via {@code removedAt}; re-add reactivates the row. */
@Entity
@Table(schema = "teams", name = "team_members")
public class TeamMemberEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "team_id")
    private UUID teamId;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "added_at")
    private Instant addedAt;

    @Column(name = "removed_at")
    private Instant removedAt;

    @Transient
    private boolean isNew = true;

    protected TeamMemberEntity() {
    }

    public TeamMemberEntity(UUID id, UUID teamId, String userId) {
        this.id = id;
        this.teamId = teamId;
        this.userId = userId;
        this.addedAt = Instant.now();
        this.removedAt = null;
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

    /** Reactivate a previously soft-deleted membership (re-add). */
    public void reactivate() {
        this.removedAt = null;
        this.addedAt = Instant.now();
    }

    public void softDelete() {
        this.removedAt = Instant.now();
    }

    public UUID getTeamId() {
        return teamId;
    }

    public String getUserId() {
        return userId;
    }

    public Instant getAddedAt() {
        return addedAt;
    }

    public Instant getRemovedAt() {
        return removedAt;
    }
}
