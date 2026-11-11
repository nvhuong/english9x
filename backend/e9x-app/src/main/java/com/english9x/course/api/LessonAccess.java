package com.english9x.course.api;

/** The answer of {@link LessonGate}. {@code NOT_ENTITLED} wins over {@code LOCKED}: somebody with no entitlement is told to buy, not to finish the previous lesson. */
public enum LessonAccess { ALLOWED, NOT_ENTITLED, LOCKED }
