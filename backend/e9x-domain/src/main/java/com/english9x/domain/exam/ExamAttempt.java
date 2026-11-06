package com.english9x.domain.exam;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Server-timed exam attempt: sections run in order and cannot be revisited. */
public final class ExamAttempt {
    private final String id;
    private final List<SectionPlan> sections;
    private final int maxResumes;
    private int sectionIndex;
    private Instant sectionStartedAt;
    private int resumeCount;
    private boolean interrupted;
    private AttemptStatus status = AttemptStatus.IN_PROGRESS;
    private SubmitReason submitReason;
    private Instant submittedAt;

    private ExamAttempt(String id, List<SectionPlan> sections, int maxResumes, Instant now) {
        this.id = id;
        this.sections = sections;
        this.maxResumes = maxResumes;
        this.sectionStartedAt = now;
    }

    public static ExamAttempt start(String id, List<SectionPlan> sections, int maxResumes, Instant now) {
        if (id == null || id.isBlank() || sections == null || sections.isEmpty()
                || maxResumes < 0 || now == null) {
            throw new IllegalArgumentException("invalid exam attempt");
        }
        return new ExamAttempt(id, List.copyOf(sections), maxResumes, now);
    }

    public String id() {
        return id;
    }

    /** Current section, advancing past expired sections; empty once submitted. */
    public Optional<SectionPlan> currentSection(Instant now) {
        sync(now);
        return status == AttemptStatus.SUBMITTED ? Optional.empty() : Optional.of(sections.get(sectionIndex));
    }

    public Instant currentSectionEndsAt(Instant now) {
        sync(now);
        if (status == AttemptStatus.SUBMITTED) {
            throw new IllegalStateException("attempt already submitted");
        }
        return sectionEnd();
    }

    public void finishSectionEarly(Instant now) {
        requireInProgress();
        int before = sectionIndex;
        sync(now);
        if (status == AttemptStatus.SUBMITTED || sectionIndex != before) {
            return; // time ran out first; the expired section already moved on
        }
        if (sectionIndex == sections.size() - 1) {
            submit(now, SubmitReason.LEARNER);
        } else {
            sectionIndex++;
            sectionStartedAt = now;
        }
    }

    public boolean canAnswer(String sectionId, Instant now) {
        sync(now);
        return status == AttemptStatus.IN_PROGRESS && sections.get(sectionIndex).id().equals(sectionId);
    }

    public ResumeOutcome resume(Instant now) {
        requireInProgress();
        if (now == null) {
            throw new IllegalArgumentException("now is required");
        }
        resumeCount++;
        if (resumeCount > maxResumes) {
            interrupted = true;
            submit(now, SubmitReason.RESUME_LIMIT);
            return ResumeOutcome.AUTO_SUBMITTED;
        }
        return ResumeOutcome.CONTINUED;
    }

    /** Returns true on the first submission; later calls change nothing and return false. */
    public boolean submit(Instant now, SubmitReason reason) {
        if (now == null || reason == null) {
            throw new IllegalArgumentException("now and reason are required");
        }
        if (status == AttemptStatus.SUBMITTED) {
            return false;
        }
        status = AttemptStatus.SUBMITTED;
        submitReason = reason;
        submittedAt = now;
        return true;
    }

    public AttemptStatus status() {
        return status;
    }

    public int resumeCount() {
        return resumeCount;
    }

    public boolean interrupted() {
        return interrupted;
    }

    public Optional<SubmitReason> submitReason() {
        return Optional.ofNullable(submitReason);
    }

    public Optional<Instant> submittedAt() {
        return Optional.ofNullable(submittedAt);
    }

    private Instant sectionEnd() {
        return sectionStartedAt.plus(sections.get(sectionIndex).duration());
    }

    private void requireInProgress() {
        if (status == AttemptStatus.SUBMITTED) {
            throw new IllegalStateException("attempt already submitted");
        }
    }

    private void sync(Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("now is required");
        }
        while (status == AttemptStatus.IN_PROGRESS && !now.isBefore(sectionEnd())) {
            Instant end = sectionEnd();
            if (sectionIndex == sections.size() - 1) {
                submit(end, SubmitReason.TIME_UP);
            } else {
                sectionIndex++;
                sectionStartedAt = end;
            }
        }
    }
}
