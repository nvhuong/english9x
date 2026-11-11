package com.english9x.grading.ai.api;

/** Asks the model to grade one response with one grading skill. Persists nothing (the caller decides what to keep). */
public interface AiGrader {

    /** @throws AiGradingException when no valid answer could be had; {@link AiGradingException#kind()} says whether trying later may help */
    AiResult grade(SkillVersion skill, Task task, Response response);
}
