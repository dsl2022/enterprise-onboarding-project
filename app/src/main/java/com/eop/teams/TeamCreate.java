package com.eop.teams;

/** Frozen contract {@code TeamCreate}. {@code name} required. */
public record TeamCreate(String name, String description) {
}
