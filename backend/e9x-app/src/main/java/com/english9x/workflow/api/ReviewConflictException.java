package com.english9x.workflow.api;

/** The action does not fit the current state (already decided, not in the right status, lost a race). */
public class ReviewConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ReviewConflictException(String message) {
        super(message);
    }
}
