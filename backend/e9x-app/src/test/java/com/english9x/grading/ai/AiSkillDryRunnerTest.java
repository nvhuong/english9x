package com.english9x.grading.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.english9x.content.skill.api.GradingSkillVersionView;
import com.english9x.content.skill.api.SkillDryRunner;
import com.english9x.grading.ai.api.AiGradingException;
import com.english9x.grading.ai.api.AiResult;
import com.english9x.grading.ai.api.Response;
import com.english9x.grading.ai.api.SkillVersion;
import com.english9x.grading.ai.api.Task;
import com.english9x.workflow.api.ContentStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** The dry run goes through the same AiGrader as the grading job, with the skill version as it is, and gives its failures back by kind. */
class AiSkillDryRunnerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static GradingSkillVersionView skill() throws Exception {
        return new GradingSkillVersionView(UUID.randomUUID(), UUID.randomUUID(), 1, ContentStatus.DRAFT, Optional.empty(), "IELTS", "IELTS_W_TASK2", "the rubric",
                JSON.readTree("[{\"code\":\"a\",\"name\":\"A\",\"min\":0,\"max\":9,\"step\":0.5}]"), "{{rubric}} {{samples}} {{task}} {{response}}", JSON.readTree("[]"),
                JSON.readTree("{\"type\":\"object\"}"), UUID.randomUUID(), Instant.now(), Instant.now());
    }

    @Test
    void theSkillTheTaskAndTheAnswerGoToTheGraderAndItsResultComesBack() throws Exception {
        GradingSkillVersionView v = skill();
        AtomicReference<Object[]> seen = new AtomicReference<>();
        AiResult r = new AiResult(JSON.readTree("{\"a\":6.5}"), 6.5, "gemini-2.5-flash", 120, 80);
        AiSkillDryRunner runner = new AiSkillDryRunner((s, t, resp) -> {
            seen.set(new Object[] {s, t, resp});
            return r;
        });
        SkillDryRunner.Result out = runner.run(v, "Discuss.", new SkillDryRunner.Answer.Text("An essay"));
        assertEquals(new SkillDryRunner.Result(r.result(), 6.5, "gemini-2.5-flash", 120, 80), out);
        SkillVersion sent = (SkillVersion) seen.get()[0];
        assertEquals(v.versionId(), sent.versionId());
        assertSame(v.resultSchema(), sent.resultSchema());
        assertEquals("the rubric", sent.rubric());
        assertEquals(new Task("IELTS_W_TASK2", "Discuss."), seen.get()[1]);
        assertEquals("An essay", ((Response.Text) seen.get()[2]).text());

        runner.run(v, "Talk.", new SkillDryRunner.Answer.Audio(new byte[] {1, 2}, "audio/webm"));
        Response.Audio audio = assertInstanceOf(Response.Audio.class, seen.get()[2]);
        assertEquals("audio/webm", audio.mime());
    }

    @Test
    void eachFailureKeepsItsKind() throws Exception {
        GradingSkillVersionView v = skill();
        for (AiGradingException.Kind k : AiGradingException.Kind.values()) {
            AiSkillDryRunner runner = new AiSkillDryRunner((s, t, resp) -> {
                throw new AiGradingException(k, "why " + k);
            });
            SkillDryRunner.Failure f = assertThrows(SkillDryRunner.Failure.class, () -> runner.run(v, "t", new SkillDryRunner.Answer.Text("x")));
            assertEquals(k.name(), f.kind().name());
            assertEquals("why " + k, f.getMessage());
        }
    }
}
