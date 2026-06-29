package com.eop.platform;

/** A contract endpoint that exists but is deliberately not built in v1 → RFC-7807 501 (e.g. the assistant stub). */
public class NotImplementedException extends RuntimeException {
    public NotImplementedException(String message) {
        super(message);
    }
}
