package com.eop.review;

import java.time.Instant;

/** Frozen contract {@code ReviewItem}. */
public record ReviewItem(String id, String kind, String title, String requester, Instant submittedAt) {
}
