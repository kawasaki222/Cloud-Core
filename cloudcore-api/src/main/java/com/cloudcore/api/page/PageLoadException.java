package com.cloudcore.api.page;

/**
 * A page could not be read, parsed, composed or validated.
 */
public class PageLoadException extends Exception {
    public PageLoadException(String message) {
        super(message);
    }

    public PageLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
