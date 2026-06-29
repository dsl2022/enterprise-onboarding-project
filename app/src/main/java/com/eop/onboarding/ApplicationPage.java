package com.eop.onboarding;

import java.util.List;

/** Frozen contract {@code ApplicationPage} (cursor pagination). */
public record ApplicationPage(List<Application> items, String nextCursor) {
}
