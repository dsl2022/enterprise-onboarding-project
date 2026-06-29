package com.eop.auth;

/**
 * Validates a user-supplied post-login {@code returnTo} target. The SPA (served at
 * {@code /app}) asks to be sent back to where it started after the OAuth round-trip;
 * we only ever honor a <b>same-origin relative path</b> so the parameter can never be
 * turned into an open redirect to an attacker-controlled site.
 */
final class SafeRelativePath {

    /** Session attribute the login entry stashes and the success handler consumes. */
    static final String RETURN_TO_SESSION_ATTR = "EOP_RETURN_TO";

    private SafeRelativePath() {}

    static boolean isValid(String path) {
        return path != null
                && path.startsWith("/")     // must be a relative path…
                && !path.startsWith("//")   // …but not protocol-relative (//evil.com)
                && !path.contains("\\")     // no backslash smuggling
                && !path.contains("://");   // no embedded absolute URL
    }
}
