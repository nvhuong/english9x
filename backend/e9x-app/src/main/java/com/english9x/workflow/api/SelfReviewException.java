package com.english9x.workflow.api;

import org.springframework.security.access.AccessDeniedException;

/** The reviewer wrote or submitted the version (spec §4). A 403 like any other refusal; nothing is changed. */
public class SelfReviewException extends AccessDeniedException {
    private static final long serialVersionUID = 1L;

    public SelfReviewException() {
        super("a reviewer may not review their own version");
    }
}
