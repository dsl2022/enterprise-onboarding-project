package com.eop.platform;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Opaque cursor for list pagination. v1 uses simple page-number cursors (base64-encoded); the API stays
 * cursor-shaped so a later switch to keyset pagination needs no contract change.
 */
public final class CursorCodec {

    private CursorCodec() {
    }

    /** Decode a cursor to a 0-based page number; null/blank/garbage → page 0. */
    public static int toPage(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)));
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    /** Encode the next page number, or null when there is no next page. */
    public static String nextCursor(int currentPage, boolean hasNext) {
        if (!hasNext) {
            return null;
        }
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Integer.toString(currentPage + 1).getBytes(StandardCharsets.UTF_8));
    }
}
