package com.eop.platform;

/** The target resource does not exist → RFC-7807 404. */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
