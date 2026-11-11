package com.english9x.exam.internal;

import com.english9x.billing.api.Entitlements;
import com.english9x.content.media.api.MediaUrls;
import com.english9x.content.media.api.StagedFiles;
import com.english9x.content.test.api.TestExamView;
import com.english9x.content.test.api.TestVersions;
import com.english9x.domain.exam.AttemptStatus;
import com.english9x.domain.exam.ExamAttempt;
import com.english9x.domain.exam.ResumeOutcome;
import com.english9x.domain.exam.SectionPlan;
import com.english9x.domain.exam.SubmitReason;
import com.english9x.exam.api.events.AttemptSubmitted;
import com.english9x.exam.internal.ExamDtos.AttemptView;
import com.english9x.exam.internal.ExamDtos.AudioLink;
import com.english9x.exam.internal.ExamDtos.Result;
import com.english9x.exam.internal.ExamDtos.SavedResponse;
import com.english9x.exam.internal.ExamDtos.SectionContent;
import com.english9x.exam.internal.ExamDtos.SectionSummary;
import com.english9x.exam.internal.ExamRepository.Conflict;
import com.english9x.exam.internal.ExamRepository.Loaded;
import com.english9x.exam.internal.ExamRepository.NewAttempt;
import com.english9x.exam.internal.ExamRepository.QuestionRef;
import com.english9x.org.api.CurrentContextHolder;
import com.english9x.platform.jobs.api.JobQueue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * The exam room's rules (spec 8, 9). The clock is the {@link Clock} bean, never the client's: every state change loads the aggregate, asks the domain
 * ({@link ExamAttempt}) with {@code now}, and saves with the optimistic lock ({@code version}); on a lost race it reloads and tries ONCE more, and by then the
 * other writer's state decides (a second submission finds SUBMITTED and does nothing, so there is one result and one event).
 *
 * <p>{@link AttemptSubmitted} is published inside the transaction that submits, through the event publication registry: it is delivered after the commit and a
 * rolled-back (lost-race) transaction publishes nothing.
 */
@Service
class ExamService {

    private static final Logger LOG = LoggerFactory.getLogger(ExamService.class);
    static final String AUTO_SUBMIT = "auto_submit_attempt";
    private static final int TRIES = 2;

    private final ExamRepository repo;
    private final TestVersions tests;
    private final Entitlements entitlements;
    private final MediaUrls mediaUrls;
    private final StagedFiles staged;
    private final JobQueue queue;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final ExamProperties props;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;

    ExamService(ExamRepository repo, TestVersions tests, Entitlements entitlements, MediaUrls mediaUrls, StagedFiles staged, JobQueue queue,
                ApplicationEventPublisher events, Clock clock, ExamProperties props, ObjectMapper json, PlatformTransactionManager tm) {
        this.repo = repo;
        this.tests = tests;
        this.entitlements = entitlements;
        this.mediaUrls = mediaUrls;
        this.staged = staged;
        this.queue = queue;
        this.events = events;
        this.clock = clock;
        this.props = props;
        this.json = json;
        this.tx = new TransactionTemplate(tm);
        this.readTx = new TransactionTemplate(tm);
        this.readTx.setReadOnly(true);
    }

    // ---- start ------------------------------------------------------------------------------------------------------------------------

    record Started(boolean created, AttemptView view) {}

    Started start(UUID testVersionId) {
        if (testVersionId == null) {
            throw ExamProblems.badRequest("exam-test-missing", "testVersionId is required.");
        }
        UUID user = me();
        for (int i = 0; i < TRIES; i++) {
            try {
                return tx.execute(st -> startOnce(user, testVersionId));
            } catch (DuplicateKeyException e) {
                // a concurrent start of the same test by the same person won the unique index: answer with theirs
            }
            Optional<UUID> existing = readTx.execute(st -> repo.inProgress(user, testVersionId));
            if (existing != null && existing.isPresent()) {
                return new Started(false, view(existing.get(), user));
            }
        }
        throw ExamProblems.rejected("exam-busy", "The attempt could not be started; try again.");
    }

    private Started startOnce(UUID user, UUID testVersionId) {
        Instant now = clock.instant();
        Optional<UUID> existing = repo.inProgress(user, testVersionId);
        if (existing.isPresent()) {
            Loaded l = repo.load(existing.get(), user).orElseThrow(ExamProblems::notFound);
            boolean running = l.attempt().currentSection(now).isPresent();
            if (running) {
                return new Started(false, build(l, now));
            }
            closeExpired(l, now); // its time is up (the job has not fired yet): close it now, then start afresh
        }
        TestExamView test = tests.forExamRoom(testVersionId).orElseThrow(ExamProblems::notFound);
        if (test.parts().isEmpty()) {
            throw ExamProblems.notFound();
        }
        UUID course = entitledCourse(user, test.examCode(), now).orElseThrow(ExamProblems::notEntitled);
        int maxResumes = test.maxResumes() != null ? test.maxResumes() : props.defaultMaxResumes();
        List<SectionPlan> plans = new ArrayList<>();
        List<String> skills = new ArrayList<>();
        List<QuestionRef> questions = new ArrayList<>();
        for (TestExamView.Part p : test.parts()) {
            plans.add(new SectionPlan(p.sectionCode(), Duration.ofSeconds(p.durationSec())));
            skills.add(p.skill());
            for (TestExamView.Group g : p.groups()) {
                g.questions().forEach(q -> questions.add(new QuestionRef(q.questionId(), q.versionId(), p.sectionCode())));
            }
        }
        UUID id = UUID.randomUUID();
        ExamAttempt attempt = ExamAttempt.start(id.toString(), plans, maxResumes, now);
        UUID org = CurrentContextHolder.current().flatMap(CurrentContextHolder.Binding::orgId).orElse(null);
        repo.insert(new NewAttempt(id, user, org, testVersionId, course, test.examCode(), maxResumes, now, attempt, skills), questions);
        schedule(id, user, org, attempt.deadline());
        Loaded l = repo.load(id, user).orElseThrow();
        return new Started(true, build(l, now));
    }

    private Optional<UUID> entitledCourse(UUID user, String examCode, Instant now) {
        for (UUID course : repo.entitledCourses(user, examCode)) {
            if (entitlements.activeFor(user, course, now)) {
                return Optional.of(course);
            }
        }
        return Optional.empty();
    }

    private void closeExpired(Loaded l, Instant now) {
        l.attempt().submit(now, SubmitReason.TIME_UP); // already past its deadline: the domain records TIME_UP at the deadline
        repo.save(l);
        publish(l);
    }

    // ---- reads ------------------------------------------------------------------------------------------------------------------------

    AttemptView view(UUID attemptId, UUID user) {
        AttemptView v = readTx.execute(st -> {
            Instant now = clock.instant();
            Loaded l = repo.load(attemptId, user).orElseThrow(ExamProblems::notFound);
            return build(l, now);
        });
        return v;
    }

    private AttemptView build(Loaded l, Instant now) {
        ExamAttempt a = l.attempt();
        Optional<SectionPlan> current = a.currentSection(now);
        List<SectionSummary> sections = new ArrayList<>();
        for (int i = 0; i < a.sectionCount(); i++) {
            String state = current.isEmpty() || i < a.sectionIndex() ? "DONE" : i == a.sectionIndex() ? "CURRENT" : "PENDING";
            sections.add(new SectionSummary(a.section(i).id(), i, l.skills().get(i), (int) a.section(i).duration().toSeconds(), state));
        }
        Result result = a.status() == AttemptStatus.SUBMITTED ? result(l) : null;
        SectionContent content = null;
        Instant endsAt = null;
        List<SavedResponse> saved = List.of();
        String title = null;
        if (current.isPresent()) {
            int i = a.sectionIndex();
            endsAt = a.currentSectionEndsAt(now);
            TestExamView test = tests.forExamRoom(l.testVersionId()).orElseThrow(ExamProblems::notFound);
            title = test.title();
            String code = current.get().id();
            TestExamView.Part part = test.parts().stream().filter(p -> p.sectionCode().equals(code)).findFirst().orElseThrow(ExamProblems::notFound);
            content = new SectionContent(code, l.skills().get(i), (int) a.section(i).duration().toSeconds(), a.sectionStartedAt(i), endsAt, part.groups());
            saved = repo.savedOfSection(l.id(), code, json).stream().map(s -> new SavedResponse(s.questionId(), s.value(), s.savedAt())).toList();
        }
        return new AttemptView(l.id(), l.testVersionId(), title, l.examCode(), a.status().name(), sections, content, endsAt, now, a.resumeCount(), a.maxResumes(), saved, result);
    }

    private static Result result(Loaded l) {
        ExamAttempt a = l.attempt();
        return new Result(l.id(), a.status().name(), a.submittedAt().orElse(null), a.submitReason().map(Enum::name).orElse(null), a.interrupted());
    }

    // ---- state changes ----------------------------------------------------------------------------------------------------------------

    /**
     * Load, change through the domain, save with the version check; on a lost race reload and run again (once). Publishes {@link AttemptSubmitted} when THIS
     * transaction is the one that submitted, and keeps a job queued for the (possibly earlier) deadline.
     */
    <T> T mutate(UUID attemptId, UUID user, boolean gate, BiFunction<Loaded, Instant, T> change) {
        for (int i = 0; ; i++) {
            try {
                return tx.execute(st -> {
                    Instant now = clock.instant();
                    Loaded l = repo.load(attemptId, user).orElseThrow(ExamProblems::notFound);
                    if (l.attempt().status() == AttemptStatus.SUBMITTED) {
                        return change.apply(l, now); // nothing to save: a submitted attempt never changes again
                    }
                    if (gate) {
                        requireContinue(l);
                    }
                    Instant before = l.attempt().deadline();
                    T out = change.apply(l, now);
                    repo.save(l);
                    publish(l);
                    if (l.attempt().status() == AttemptStatus.IN_PROGRESS && !l.attempt().deadline().equals(before)) {
                        schedule(l.id(), l.userId(), l.orgId(), l.attempt().deadline());
                    }
                    return out;
                });
            } catch (Conflict c) {
                if (i + 1 >= TRIES) {
                    throw ExamProblems.rejected("exam-busy", "The attempt was changed at the same time; try again.");
                }
            }
        }
    }

    private void requireContinue(Loaded l) {
        if (l.courseId() == null || !entitlements.canContinueAttempt(l.userId(), l.courseId(), l.startedAt())) {
            throw ExamProblems.notEntitled();
        }
    }

    private void publish(Loaded l) {
        ExamAttempt a = l.attempt();
        if (a.status() == AttemptStatus.SUBMITTED && !l.wasSubmitted()) {
            events.publishEvent(new AttemptSubmitted(l.id(), l.userId(), l.testVersionId(), Optional.ofNullable(l.orgId()), a.submittedAt().orElseThrow(),
                    a.submitReason().orElseThrow().name(), a.interrupted()));
        }
    }

    private void schedule(UUID attemptId, UUID user, UUID org, Instant deadline) {
        ObjectNode payload = json.createObjectNode().put("attemptId", attemptId.toString()).put("userId", user.toString());
        if (org != null) {
            payload.put("orgId", org.toString());
        }
        queue.enqueueOnce(AUTO_SUBMIT, payload, deadline, attemptId + ":" + deadline.toEpochMilli());
    }

    record Resumed(ResumeOutcome outcome, AttemptView view) {}

    Resumed resume(UUID attemptId) {
        UUID user = me();
        ResumeOutcome outcome = mutate(attemptId, user, true, (l, now) -> l.attempt().status() == AttemptStatus.SUBMITTED ? ResumeOutcome.AUTO_SUBMITTED : l.attempt().resume(now));
        return new Resumed(outcome, view(attemptId, user));
    }

    AttemptView finishSection(UUID attemptId, String sectionId) {
        UUID user = me();
        mutate(attemptId, user, true, (l, now) -> {
            ExamAttempt a = l.attempt();
            if (a.status() == AttemptStatus.SUBMITTED) {
                return null;
            }
            Optional<SectionPlan> current = a.currentSection(now);
            if (current.isPresent() && !current.get().id().equals(sectionId)) {
                throw ExamProblems.rejected("exam-section-not-current", "That section is not the one being sat.");
            }
            if (current.isPresent()) {
                a.finishSectionEarly(now);
            }
            return null;
        });
        return view(attemptId, user);
    }

    Result submit(UUID attemptId) {
        UUID user = me();
        return submitAs(attemptId, user, SubmitReason.LEARNER, true);
    }

    Result submitAs(UUID attemptId, UUID user, SubmitReason reason, boolean gate) {
        return mutate(attemptId, user, gate, (l, now) -> {
            l.attempt().submit(now, reason);
            return result(l);
        });
    }

    /** The auto-submit job: submits when the time is up (the domain decides), otherwise leaves the attempt and makes sure a job waits for the deadline. */
    void autoSubmit(UUID attemptId, UUID user) {
        mutate(attemptId, user, false, (l, now) -> {
            l.attempt().currentSection(now); // brings the attempt up to date: past the last section it is submitted (TIME_UP)
            return null;
        });
    }

    // ---- answers ----------------------------------------------------------------------------------------------------------------------

    void saveResponse(UUID attemptId, UUID questionId, ExamDtos.SaveRequest body) {
        if (body == null || body.answer() == null || body.answeredAt() == null) {
            throw ExamProblems.badRequest("exam-response-invalid", "answer and answeredAt are required.");
        }
        if (body.answer().toString().length() > props.maxAnswerSize().toBytes()) {
            throw ExamProblems.tooLarge("The answer");
        }
        UUID user = me();
        tx.executeWithoutResult(st -> {
            Instant now = clock.instant();
            Loaded l = repo.load(attemptId, user).orElseThrow(ExamProblems::notFound);
            requireContinue(l);
            String section = repo.sectionOfQuestion(attemptId, questionId).orElseThrow(ExamProblems::notFound);
            if (!l.attempt().acceptsAnswer(section, body.answeredAt(), now, props.lateSyncGrace())) {
                throw ExamProblems.rejected("exam-response-rejected", "The time of that section is over.");
            }
            repo.upsertResponse(attemptId, questionId, user, body.answer(), body.answeredAt(), now);
        });
    }

    // ---- audio ------------------------------------------------------------------------------------------------------------------------

    AudioLink audio(UUID attemptId, UUID mediaId) {
        UUID user = me();
        AudioLink link = tx.execute(st -> {
            Instant now = clock.instant();
            Loaded l = repo.load(attemptId, user).orElseThrow(ExamProblems::notFound);
            requireContinue(l);
            Optional<SectionPlan> current = l.attempt().currentSection(now);
            if (current.isEmpty()) {
                throw ExamProblems.rejected("exam-submitted", "The attempt is over.");
            }
            TestExamView test = tests.forExamRoom(l.testVersionId()).orElseThrow(ExamProblems::notFound);
            Set<UUID> allowed = new LinkedHashSet<>();
            test.parts().stream().filter(p -> p.sectionCode().equals(current.get().id())).forEach(p -> p.groups().forEach(g -> {
                audioIds(g.content(), allowed);
                g.questions().forEach(q -> audioIds(q.content(), allowed));
            }));
            if (!allowed.contains(mediaId)) {
                throw ExamProblems.notFound();
            }
            MediaUrls.SignedUrl signed = mediaUrls.signedUrl(mediaId);
            if (!repo.markAudioPlayed(attemptId, mediaId, user, now)) {
                throw ExamProblems.gone();
            }
            return new AudioLink(signed.url().toString(), signed.expiresAt());
        });
        return link;
    }

    /** Ids of the files a content tree names as audio ({@code audioMediaId} and the like). */
    static void audioIds(JsonNode node, Set<UUID> out) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                String name = e.getKey().toLowerCase(java.util.Locale.ROOT);
                if (name.startsWith("audio") && name.endsWith("mediaid") && e.getValue().isTextual()) {
                    try {
                        out.add(UUID.fromString(e.getValue().asText()));
                    } catch (IllegalArgumentException ignored) {
                        // not an id: not a file of ours
                    }
                } else {
                    audioIds(e.getValue(), out);
                }
            });
        } else if (node.isArray()) {
            node.forEach(c -> audioIds(c, out));
        }
    }

    // ---- speaking recordings ------------------------------------------------------------------------------------------------------------

    private static final byte[] EBML = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3};

    void recording(UUID attemptId, UUID questionId, MultipartFile file) {
        // whose attempt it is comes first: a stranger learns nothing about the rules of the upload
        readTx.executeWithoutResult(st -> repo.load(attemptId, me()).orElseThrow(ExamProblems::notFound));
        if (file == null || file.isEmpty()) {
            throw ExamProblems.unsupportedRecording();
        }
        if (file.getSize() > props.maxRecordingSize().toBytes()) {
            throw ExamProblems.tooLarge("The recording");
        }
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase(java.util.Locale.ROOT);
        if (!type.startsWith("audio/webm") && !type.startsWith("video/webm")) {
            throw ExamProblems.unsupportedRecording();
        }
        UUID user = me();
        Path tmp = null;
        try {
            byte[] head = new byte[4];
            try (InputStream in = file.getInputStream()) {
                if (in.readNBytes(head, 0, 4) != 4 || !java.util.Arrays.equals(head, EBML)) {
                    throw ExamProblems.unsupportedRecording();
                }
            }
            tmp = Files.createTempFile("e9x-rec-", ".webm");
            file.transferTo(tmp);
            Path upload = tmp;
            tx.executeWithoutResult(st -> {
                Instant now = clock.instant();
                Loaded l = repo.load(attemptId, user).orElseThrow(ExamProblems::notFound);
                requireContinue(l);
                String section = repo.sectionOfQuestion(attemptId, questionId).orElseThrow(ExamProblems::notFound);
                ExamAttempt a = l.attempt();
                a.currentSection(now); // bring the windows up to date
                int i = indexOf(a, section);
                if (i < 0 || !"SPEAKING".equals(l.skills().get(i)) || a.sectionStartedAt(i) == null) {
                    throw ExamProblems.rejected("exam-recording-rejected", "That question is not in the Speaking part.");
                }
                Instant start = a.sectionStartedAt(i);
                Instant end = a.sectionEndedAt(i) != null ? a.sectionEndedAt(i) : start.plus(a.section(i).duration());
                if (now.isBefore(start) || !now.isBefore(end.plus(props.recordingUploadTolerance()))) {
                    throw ExamProblems.rejected("exam-recording-rejected", "The Speaking part is over.");
                }
                String key = "staging/recordings/" + attemptId + "/" + questionId + ".webm";
                staged.put(key, upload, "audio/webm");
                repo.upsertRecording(attemptId, questionId, user, key, "audio/webm", file.getSize(), now);
            });
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the upload", e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException e) {
                    LOG.warn("a temp file could not be removed");
                }
            }
        }
    }

    private static int indexOf(ExamAttempt a, String section) {
        for (int i = 0; i < a.sectionCount(); i++) {
            if (a.section(i).id().equals(section)) {
                return i;
            }
        }
        return -1;
    }

    private static UUID me() {
        return CurrentContextHolder.current().orElseThrow(() -> new AccessDeniedException("no user")).userId();
    }
}
