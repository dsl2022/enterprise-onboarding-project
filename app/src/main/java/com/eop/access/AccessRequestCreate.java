package com.eop.access;

/** Frozen contract {@code AccessRequestCreate}. {@code resourceId} + {@code justification} required. */
public record AccessRequestCreate(String resourceId, String justification, String duration) {
}
