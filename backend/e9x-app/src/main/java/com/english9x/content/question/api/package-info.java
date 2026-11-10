/**
 * Public surface of the question part of the {@code content} module (P2.3). Two sides, never mixed:
 * <ul>
 *   <li>{@link com.english9x.content.question.api.QuestionVersions} and {@link com.english9x.content.question.api.QuestionGroupVersions}
 *       {@code examView}: the CONTENT side of a PUBLISHED (or later ARCHIVED) version, for the exam room, the lessons and the previews. The records
 *       it returns ({@code QuestionExamView}, {@code QuestionGroupExamView}) have no answer key, no explanation, no transcript, no sample answer.
 *   <li>{@code find} and {@code versions}: the staff side, with the answer key, read in the caller's transaction so Row-Level Security answers: only
 *       the AUTHOR or REVIEWER of the exam, in the version's own context. For the CMS editor, the preview (P2.8) and the version comparison (P2.9).
 * </ul>
 * The review type names are {@link com.english9x.content.question.api.QuestionVersions#REVIEW_TYPE} and
 * {@link com.english9x.content.question.api.QuestionGroupVersions#REVIEW_TYPE}: other content lists them as {@code VersionRef} dependencies.
 * Everything else in {@code content.question} is internal.
 */
@org.springframework.modulith.NamedInterface("question")
package com.english9x.content.question.api;
