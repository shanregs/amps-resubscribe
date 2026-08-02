package com.bmo.amps.resubscribe.util;

/**
 * Wraps checked {@code com.crankuptheamps.client.exception.AMPSException} at the framework's public
 * API boundary (DESIGN.md {@literal §}8 interfaces are checked-exception-free by design).
 */
public class AmpsFrameworkException extends RuntimeException {

    public AmpsFrameworkException(String message, Throwable cause) {
        super(message, cause);
    }
}
