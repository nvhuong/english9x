package com.english9x.platform.jobs;

import com.english9x.platform.jobs.api.Job;
import java.util.UUID;

/** A job together with the lease token that fences its result against a newer claim. */
record ClaimedJob(Job job, UUID leaseToken) {}
