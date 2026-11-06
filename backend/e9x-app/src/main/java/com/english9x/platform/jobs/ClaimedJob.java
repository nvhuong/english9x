package com.english9x.platform.jobs;

import com.english9x.platform.jobs.api.Job;
import java.util.UUID;

/**
 * A job together with the lease token that fences its result against a newer claim.
 *
 * @param reclaimed true when the claim took over a RUNNING job whose lease had expired
 */
record ClaimedJob(Job job, UUID leaseToken, boolean reclaimed) {}
