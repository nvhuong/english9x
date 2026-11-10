/**
 * Public surface of the test builder (P2.6) in the {@code content} module. Two sides, never mixed:
 * <ul>
 *   <li>{@link com.english9x.content.test.api.TestVersions#forExamRoom}: what a learner is shown of a PUBLISHED (or later ARCHIVED) test version,
 *       {@link com.english9x.content.test.api.TestExamView}: the parts and, in each, the groups with the CONTENT side of their questions. Every type
 *       in it is built from the question and group exam views, which have no answer key, no explanation, no transcript, no sample answer, no notes.
 *   <li>{@link com.english9x.content.test.api.TestVersions#find}: the staff view (ids and structure; the solution side stays in the question module).
 * </ul>
 * The review type name is {@link com.english9x.content.test.api.TestVersions#REVIEW_TYPE}. Everything else in {@code content.test} is internal.
 */
@org.springframework.modulith.NamedInterface("test")
package com.english9x.content.test.api;
