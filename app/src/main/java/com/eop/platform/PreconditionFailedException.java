package com.eop.platform;

/** The caller's If-Match/ETag is stale (the resource changed) → RFC-7807 412. */
public class PreconditionFailedException extends RuntimeException {
    public PreconditionFailedException(String message) {
        super(message);
    }
}
