package com.eop.platform;

/** Semantically invalid request — e.g. an Idempotency-Key reused with a different body → RFC-7807 422. */
public class UnprocessableException extends RuntimeException {
    public UnprocessableException(String message) {
        super(message);
    }
}
