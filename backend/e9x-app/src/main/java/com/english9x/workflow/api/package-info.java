/**
 * Public surface of the {@code workflow} module: the review workflow every content module (P2) plugs into. Content
 * modules implement {@link com.english9x.workflow.api.ReviewableType} (one bean per content type) and call
 * {@link com.english9x.workflow.api.ReviewService}; everything else in {@code workflow} is internal.
 */
@org.springframework.modulith.NamedInterface("api")
package com.english9x.workflow.api;
