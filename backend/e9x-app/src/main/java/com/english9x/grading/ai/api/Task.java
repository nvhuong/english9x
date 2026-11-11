package com.english9x.grading.ai.api;

/** What the learner was asked: the text of the question (the essay prompt, the speaking topic and bullet points). */
public record Task(String typeCode, String text) {}
