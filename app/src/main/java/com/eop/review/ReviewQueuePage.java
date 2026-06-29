package com.eop.review;

import java.util.List;

/** Frozen contract {@code ReviewQueuePage} (cursor pagination). */
public record ReviewQueuePage(List<ReviewItem> items, String nextCursor) {
}
