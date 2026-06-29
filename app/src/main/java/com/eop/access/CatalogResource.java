package com.eop.access;

/** Frozen contract {@code CatalogResource}. */
public record CatalogResource(
        String id,
        String name,
        String type,
        String risk,
        String description,
        String mappedGroup) {
}
