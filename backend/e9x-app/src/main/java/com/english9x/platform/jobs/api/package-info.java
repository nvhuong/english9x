/**
 * Public surface of the background job queue (spec §5.3): enqueue from a request path, handle in a
 * {@link com.english9x.platform.jobs.api.JobHandler}. Everything else in {@code platform.jobs} is internal.
 */
@org.springframework.modulith.NamedInterface("api")
package com.english9x.platform.jobs.api;
