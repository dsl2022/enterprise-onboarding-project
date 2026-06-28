package com.eop.auth;

import com.eop.authz.PortalRole;

/**
 * Body of {@code POST /impersonation}. v1 ships role-level impersonation ({@code role}); {@code user} is
 * reserved for future user-level impersonation (ignored in v1) so adding it later is not a contract change.
 */
public record ImpersonationRequestDto(PortalRole role, String user) {
}
