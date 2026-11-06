package com.english9x.platform.jobs.api;

/**
 * Outcome of one run. A {@link #failure(String)} result and a thrown exception are handled the same way: the job is
 * retried after the configured delay, or marked FAILED after the last retry.
 */
public final class JobResult {

    private static final JobResult SUCCESS = new JobResult(null);

    private final String error;

    private JobResult(String error) {
        this.error = error;
    }

    public static JobResult success() {
        return SUCCESS;
    }

    /**
     * @param reason stored as the job's last error (cut to a fixed length). Must not contain payload contents.
     */
    public static JobResult failure(String reason) {
        return new JobResult(reason == null || reason.isBlank() ? "failed" : reason);
    }

    public boolean isSuccess() {
        return error == null;
    }

    /** The failure reason, or {@code null} on success. */
    public String error() {
        return error;
    }

    @Override
    public String toString() {
        return isSuccess() ? "JobResult[success]" : "JobResult[failure]";
    }
}
