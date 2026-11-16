package com.english9x.grading.ai;

import com.english9x.content.skill.api.GradingSkillVersionView;
import com.english9x.content.skill.api.SkillDryRunner;
import com.english9x.grading.ai.api.AiGrader;
import com.english9x.grading.ai.api.AiGradingException;
import com.english9x.grading.ai.api.AiResult;
import com.english9x.grading.ai.api.Response;
import com.english9x.grading.ai.api.SkillVersion;
import com.english9x.grading.ai.api.Task;
import org.springframework.stereotype.Component;

/**
 * The CMS dry run of a grading skill (F16, spec 6.5) over the same {@link AiGrader} the grading job uses: same prompt, same schema validation and retries. Nothing is
 * stored: no evaluation, no score, no row the emergency daily limit counts (that limit counts graded attempts only, and is off by default).
 */
@Component
class AiSkillDryRunner implements SkillDryRunner {

    private final AiGrader ai;

    AiSkillDryRunner(AiGrader ai) {
        this.ai = ai;
    }

    @Override
    public Result run(GradingSkillVersionView skill, String task, Answer answer) {
        Response response = switch (answer) {
            case Answer.Text t -> new Response.Text(t.text());
            case Answer.Audio a -> new Response.Audio(a.bytes(), a.mime());
        };
        try {
            AiResult r = ai.grade(new SkillVersion(skill.versionId(), skill.rubric(), skill.criteria(), skill.promptTemplate(), skill.samples(), skill.resultSchema()),
                    new Task(skill.questionTypeCode(), task), response);
            return new Result(r.result(), r.band(), r.model(), r.inputTokens(), r.outputTokens());
        } catch (AiGradingException e) {
            Failure.Kind kind = switch (e.kind()) {
                case TRANSIENT -> Failure.Kind.TRANSIENT;
                case SCHEMA -> Failure.Kind.SCHEMA;
                case PERMANENT -> Failure.Kind.PERMANENT;
            };
            throw new Failure(kind, e.getMessage(), e);
        }
    }
}
