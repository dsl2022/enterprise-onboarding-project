package com.eop.platform;

/** Illegal state transition or a lost transition race (the request already moved) → RFC-7807 409. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
