package com.eop.authz;

/**
 * A request whose decision is subject to separation of duties: the principal who decides it can never be
 * the one who requested or submitted it. Both ids are checked against the <b>real</b> principal, so
 * impersonation cannot launder a self-approval.
 */
public interface SodSubject {

    String requesterId();

    String submittedById();
}
