/**
 * Public surface of the strategy-lesson part of the {@code content} module (P2.4). {@code examView} is the learner's way in (the content of a
 * PUBLISHED or ARCHIVED lesson version; embedded questions are only named by version id there, read through
 * {@code QuestionVersions#examView}, which has no answer side); {@code find} is the staff view (RLS: author or reviewer of the exam).
 * Review type name: {@link com.english9x.content.strategy.api.StrategyLessonVersions#REVIEW_TYPE}.
 */
@org.springframework.modulith.NamedInterface("strategy")
package com.english9x.content.strategy.api;
