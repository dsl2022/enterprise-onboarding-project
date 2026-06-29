package com.eop.access;

import java.util.List;

/** Frozen contract {@code CatalogPage} (cursor pagination). */
public record CatalogPage(List<CatalogResource> items, String nextCursor) {
}
