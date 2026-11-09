package com.english9x.catalog.api;

/** A section of an exam: a TOEIC part, or an IELTS skill. {@code itemCount} is how many questions a test must have there (IELTS Writing: tasks, Speaking: parts). */
public record ExamSection(String code, String skill, int position, int itemCount) {}
