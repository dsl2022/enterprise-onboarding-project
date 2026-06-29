package com.eop.directory;

/**
 * Provisions Entra <b>group membership</b> for an access grant/removal. 5a ships the simulated
 * implementation; 5b adds the real Microsoft Graph one (add/remove member via
 * {@code GroupMember.ReadWrite.All} over the WIF token). Both operations must be <b>idempotent</b> — add
 * when already a member, or remove when not a member, must succeed — so a retry after a lost
 * {@code markProvisioned} write never double-applies.
 */
public interface GroupMembershipProvisioner {

    /** Add {@code userId} to {@code groupId} (idempotent); returns an opaque grant reference. */
    String addMember(String groupId, String userId);

    /** Remove {@code userId} from {@code groupId} (idempotent — a no-op if not a member). */
    void removeMember(String groupId, String userId);
}
