package com.eop.access;

/** Internal: the projected {@link AccessRequest} plus its version, so the controller can set the ETag. */
public record AccessRequestView(AccessRequest request, int version) {
}
