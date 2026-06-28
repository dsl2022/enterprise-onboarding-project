package com.eop.authz;

import java.util.Collection;
import java.util.Set;

/**
 * A resource that an {@code OWN}-scoped permission is checked against (ABAC ownership). A principal
 * "owns" it if they are the owner or a member of its team.
 */
public interface Ownable {

    String ownerId();

    default Collection<String> teamMemberIds() {
        return Set.of();
    }
}
