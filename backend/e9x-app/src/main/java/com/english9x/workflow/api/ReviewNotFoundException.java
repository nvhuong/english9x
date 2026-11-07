package com.english9x.workflow.api;

/** The version or review does not exist, or the caller may not see it (the two are never told apart). */
public class ReviewNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ReviewNotFoundException() {
        super("review or version not found");
    }
}
