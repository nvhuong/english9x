package com.english9x.content.skill.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.english9x.catalog.api.QuestionTypes;
import com.english9x.content.media.api.MediaAssets.MediaInfo;
import com.english9x.content.media.api.MediaFiles;
import com.english9x.content.media.api.MediaKind;
import com.english9x.content.skill.api.GradingSkillVersionView;
import com.english9x.content.skill.api.SkillDryRunner;
import com.english9x.content.skill.api.SkillDryRunner.Answer;
import com.english9x.content.skill.api.SkillDryRunner.Failure;
import com.english9x.org.api.Authz;
import com.english9x.org.api.Role;
import com.english9x.workflow.api.ContentStatus;
import com.english9x.workflow.api.ReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.ErrorResponseException;

/** The dry run of a grading skill (F16): who may run it, what it sends to the AI grader, and how the grader's failures are answered. No database: the parts are mocks. */
class SkillServiceDryRunTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID SKILL = UUID.randomUUID();

    private final Authz authz = mock(Authz.class);
    private final SkillRepository repository = mock(SkillRepository.class);
    private final MediaFiles media = mock(MediaFiles.class);
    private final AtomicReference<SkillDryRunner> runner = new AtomicReference<>();
    private SkillService service;
    private GradingSkillVersionView latest;

    @BeforeEach
    void setUp() throws Exception {
        @SuppressWarnings("unchecked")
        ObjectProvider<SkillDryRunner> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenAnswer(i -> runner.get());
        service = new SkillService(authz, mock(QuestionTypes.class), mock(ReviewService.class), repository, mock(SkillValidator.class), provider, media,
                mock(PlatformTransactionManager.class));
        latest = new GradingSkillVersionView(UUID.randomUUID(), SKILL, 2, ContentStatus.DRAFT, Optional.empty(), "IELTS", "IELTS_W_TASK2", "rubric",
                JSON.readTree("[{\"code\":\"a\",\"name\":\"A\",\"min\":0,\"max\":9,\"step\":0.5}]"), "{{rubric}} {{samples}} {{task}} {{response}}", JSON.readTree("[]"),
                JSON.readTree("{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"number\"}},\"required\":[\"a\"]}"), UUID.randomUUID(), Instant.now(), Instant.now());
        when(repository.header(SKILL)).thenReturn(Optional.of(new SkillRepository.Header(SKILL, null, "IELTS", "IELTS_W_TASK2")));
        when(repository.latest(SKILL)).thenReturn(Optional.of(latest));
        when(authz.has(eq(Role.AUTHOR), eq(Optional.of("IELTS")))).thenReturn(true);
    }

    private static SkillDryRunner.Result ok() throws Exception {
        return new SkillDryRunner.Result(JSON.readTree("{\"a\":6.5}"), 6.5, "gemini-2.5-flash", 120, 80);
    }

    @Test
    void theLatestVersionRunsOnTheTextAndTheValidatedAnswerComesBack() throws Exception {
        AtomicReference<Object[]> seen = new AtomicReference<>();
        SkillDryRunner.Result result = ok();
        runner.set((skill, task, answer) -> {
            seen.set(new Object[] {skill, task, answer});
            return result;
        });
        SkillService.DryRunOutcome out = service.dryRun(SKILL, new SkillService.DryRun("Some people believe...", null, "Discuss both views."));
        assertSame(result, out.result());
        assertSame(latest, out.skill());
        assertSame(latest, seen.get()[0], "the latest version, a draft included: the author tries it before submitting");
        assertEquals("Discuss both views.", seen.get()[1]);
        assertEquals(new Answer.Text("Some people believe..."), seen.get()[2]);
    }

    @Test
    void withoutATaskTheModelIsToldThereIsNone() throws Exception {
        AtomicReference<String> task = new AtomicReference<>();
        SkillDryRunner.Result result = ok();
        runner.set((skill, t, answer) -> {
            task.set(t);
            return result;
        });
        service.dryRun(SKILL, new SkillService.DryRun("text", null, " "));
        assertEquals(SkillService.DEFAULT_TASK, task.get());
    }

    @Test
    void aReviewerMayRunItAnybodyElseMayNot() throws Exception {
        SkillDryRunner.Result result = ok();
        runner.set((skill, t, answer) -> result);
        when(authz.has(eq(Role.AUTHOR), any())).thenReturn(false);
        when(authz.has(eq(Role.REVIEWER), eq(Optional.of("IELTS")))).thenReturn(true);
        assertSame(result, service.dryRun(SKILL, new SkillService.DryRun("text", null, null)).result());
        when(authz.has(eq(Role.REVIEWER), any())).thenReturn(false);
        assertThrows(AccessDeniedException.class, () -> service.dryRun(SKILL, new SkillService.DryRun("text", null, null)));
    }

    @Test
    void anUnknownSkillIs404AndNothingIsSent() {
        runner.set((skill, t, answer) -> {
            throw new AssertionError("nothing may be sent");
        });
        ErrorResponseException e = assertThrows(ErrorResponseException.class, () -> service.dryRun(UUID.randomUUID(), new SkillService.DryRun("text", null, null)));
        assertEquals(404, e.getStatusCode().value());
    }

    @Test
    void exactlyOneOfTextAndRecording() {
        runner.set((skill, t, answer) -> {
            throw new AssertionError("nothing may be sent");
        });
        for (SkillService.DryRun bad : List.of(new SkillService.DryRun(null, null, null), new SkillService.DryRun("  ", null, null),
                new SkillService.DryRun("text", UUID.randomUUID(), null))) {
            ErrorResponseException e = assertThrows(ErrorResponseException.class, () -> service.dryRun(SKILL, bad));
            assertEquals(422, e.getStatusCode().value());
        }
        assertEquals(422, assertThrows(ErrorResponseException.class, () -> service.dryRun(SKILL, null)).getStatusCode().value());
    }

    @Test
    void aRecordingOfTheExamGoesAsAudio() throws Exception {
        UUID file = UUID.randomUUID();
        when(media.copyTo(eq(file), any(Path.class))).thenAnswer(i -> {
            Files.write(i.getArgument(1, Path.class), new byte[] {1, 2, 3});
            return new MediaInfo(file, Optional.empty(), "IELTS", MediaKind.AUDIO, "audio/mpeg", 3, Optional.of(1000L), "a.mp3");
        });
        AtomicReference<Answer> sent = new AtomicReference<>();
        SkillDryRunner.Result result = ok();
        runner.set((skill, t, answer) -> {
            sent.set(answer);
            return result;
        });
        service.dryRun(SKILL, new SkillService.DryRun(null, file, null));
        Answer.Audio audio = (Answer.Audio) sent.get();
        assertEquals("audio/mpeg", audio.mime());
        assertEquals(3, audio.bytes().length);
    }

    @Test
    void aFileThatIsNotARecordingOfTheExamOrNotVisibleIsRefusedByField() {
        UUID image = UUID.randomUUID();
        when(media.copyTo(eq(image), any(Path.class))).thenReturn(new MediaInfo(image, Optional.empty(), "IELTS", MediaKind.IMAGE, "image/png", 3, Optional.empty(), "p.png"));
        UUID toeic = UUID.randomUUID();
        when(media.copyTo(eq(toeic), any(Path.class))).thenReturn(new MediaInfo(toeic, Optional.empty(), "TOEIC", MediaKind.AUDIO, "audio/mpeg", 3, Optional.empty(), "a.mp3"));
        UUID hidden = UUID.randomUUID();
        when(media.copyTo(eq(hidden), any(Path.class))).thenThrow(new NoSuchElementException("no such file"));
        runner.set((skill, t, answer) -> {
            throw new AssertionError("nothing may be sent");
        });
        for (UUID id : List.of(image, toeic, hidden)) {
            ErrorResponseException e = assertThrows(ErrorResponseException.class, () -> service.dryRun(SKILL, new SkillService.DryRun(null, id, null)));
            assertEquals(422, e.getStatusCode().value());
            assertTrue(e.getBody().getProperties().get("errors").toString().contains("mediaId"));
        }
    }

    @Test
    void theGraderFailuresAreAnsweredByKind() {
        for (Object[] c : new Object[][] {{Failure.Kind.TRANSIENT, 503, "urn:e9x:problem:ai-grader-unavailable"}, {Failure.Kind.SCHEMA, 502, "urn:e9x:problem:dry-run-schema"},
                {Failure.Kind.PERMANENT, 502, "urn:e9x:problem:ai-grader-refused"}}) {
            runner.set((skill, t, answer) -> {
                throw new Failure((Failure.Kind) c[0], "reason " + c[0], null);
            });
            ErrorResponseException e = assertThrows(ErrorResponseException.class, () -> service.dryRun(SKILL, new SkillService.DryRun("text", null, null)));
            assertEquals(c[1], e.getStatusCode().value());
            assertEquals(c[2], e.getBody().getType().toString());
            assertTrue(e.getBody().getDetail().contains("reason " + c[0]), "the reason is told");
        }
    }

    @Test
    void withoutAnAiGraderTheAnswerIs501() {
        runner.set(null);
        ErrorResponseException e = assertThrows(ErrorResponseException.class, () -> service.dryRun(SKILL, new SkillService.DryRun("text", null, null)));
        assertEquals(501, e.getStatusCode().value());
        assertEquals("urn:e9x:problem:dry-run-unavailable", e.getBody().getType().toString());
        verify(media, never()).copyTo(any(), any());
    }
}
