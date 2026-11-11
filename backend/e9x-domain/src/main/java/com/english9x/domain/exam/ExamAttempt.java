package com.english9x.domain.exam;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Server-timed exam attempt: sections run in order and cannot be revisited. */
public final class ExamAttempt {
    private final String id;
    private final List<SectionPlan> sections;
    private final int maxResumes;
    private int sectionIndex;
    /** Actual window of each section: start when it began, end when it timed out or was finished. */
    private final Instant[] sectionStarts;
    private final Instant[] sectionEnds;
    private int resumeCount;
    private boolean interrupted;
    private AttemptStatus status = AttemptStatus.IN_PROGRESS;
    private SubmitReason submitReason;
    private Instant submittedAt;

    private ExamAttempt(String id, List<SectionPlan> sections, int maxResumes, Instant now) {
        this.id = id;
        this.sections = sections;
        this.maxResumes = maxResumes;
        this.sectionStarts = new Instant[sections.size()];
        this.sectionEnds = new Instant[sections.size()];
        this.sectionStarts[0] = now;
    }

    public static ExamAttempt start(String id, List<SectionPlan> sections, int maxResumes, Instant now) {
        if (id == null || id.isBlank() || sections == null || sections.isEmpty()
                || maxResumes < 0 || now == null) {
            throw new IllegalArgumentException("invalid exam attempt");
        }
        return new ExamAttempt(id, List.copyOf(sections), maxResumes, now);
    }

    /** Rebuilds an attempt from what was persisted (sections' real windows, counters, outcome). Null start/end entries mean "not yet". */
    public static ExamAttempt restore(String id, List<SectionPlan> sections, int maxResumes, int sectionIndex, List<Instant> starts,
                                      List<Instant> ends, int resumeCount, boolean interrupted, AttemptStatus status,
                                      SubmitReason reason, Instant submittedAt) {
        if (id == null || sections == null || sections.isEmpty() || starts.size() != sections.size() || ends.size() != sections.size()
                || sectionIndex < 0 || sectionIndex >= sections.size() || starts.get(0) == null || status == null
                || (status == AttemptStatus.SUBMITTED && (reason == null || submittedAt == null))) {
            throw new IllegalArgumentException("invalid persisted exam attempt");
        }
        ExamAttempt a = new ExamAttempt(id, List.copyOf(sections), maxResumes, starts.get(0));
        for (int i = 0; i < sections.size(); i++) {
            a.sectionStarts[i] = starts.get(i);
            a.sectionEnds[i] = ends.get(i);
        }
        a.sectionIndex = sectionIndex;
        a.resumeCount = resumeCount;
        a.interrupted = interrupted;
        a.status = status;
        a.submitReason = reason;
        a.submittedAt = submittedAt;
        return a;
    }

    public int maxResumes() {
        return maxResumes;
    }

    public int sectionIndex() {
        return sectionIndex;
    }

    public int sectionCount() {
        return sections.size();
    }

    public SectionPlan section(int i) {
        return sections.get(i);
    }

    /** When section i began (null if not reached). */
    public Instant sectionStartedAt(int i) {
        return sectionStarts[i];
    }

    /** When section i ended (timed out or finished); null while open or not reached. */
    public Instant sectionEndedAt(int i) {
        return sectionEnds[i];
    }

    /** The latest moment the attempt can still be running: end of the current section plus every later section in full. */
    public Instant deadline() {
        Instant d = sectionStarts[sectionIndex].plus(sections.get(sectionIndex).duration());
        for (int i = sectionIndex + 1; i < sections.size(); i++) {
            d = d.plus(sections.get(i).duration());
        }
        return d;
    }

    public String id() {
        return id;
    }

    public Instant startedAt() {
        return sectionStarts[0];
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
            advance(now);
        }
    }

    public boolean canAnswer(String sectionId, Instant now) {
        sync(now);
        return status == AttemptStatus.IN_PROGRESS && sections.get(sectionIndex).id().equals(sectionId);
    }

    /**
     * Whether an answer recorded offline at {@code answeredAt} and synced at {@code now} counts:
     * the section belongs to this attempt, {@code answeredAt} lies in the section's actual window
     * [start, end) (end = when it timed out or was finished early), {@code answeredAt <= now}, and
     * {@code now < end + grace}. Still true after a TIME_UP auto-submit for answers recorded in time.
     */
    public boolean acceptsAnswer(String sectionId, Instant answeredAt, Instant now, Duration grace) {
        if (sectionId == null || answeredAt == null || now == null || grace == null || grace.isNegative()) {
            throw new IllegalArgumentException("sectionId, answeredAt, now and a non-negative grace are required");
        }
        sync(now);
        int i = indexOf(sectionId);
        if (i < 0 || sectionStarts[i] == null || answeredAt.isAfter(now)) {
            return false;
        }
        Instant start = sectionStarts[i];
        Instant end = sectionEnds[i] != null ? sectionEnds[i] : sectionEnd();
        return !answeredAt.isBefore(start) && answeredAt.isBefore(end) && now.isBefore(end.plus(grace));
    }

    private int indexOf(String sectionId) {
        for (int i = 0; i < sections.size(); i++) {
            if (sections.get(i).id().equals(sectionId)) {
                return i;
            }
        }
        return -1;
    }

    public ResumeOutcome resume(Instant now) {
        requireInProgress();
        if (now == null) {
            throw new IllegalArgumentException("now is required");
        }
        sync(now);
        if (status == AttemptStatus.SUBMITTED) {
            return ResumeOutcome.AUTO_SUBMITTED; // deadline passed while away
        }
        resumeCount++;
        if (resumeCount > maxResumes) {
            interrupted = true;
            doSubmit(now, SubmitReason.RESUME_LIMIT);
            return ResumeOutcome.AUTO_SUBMITTED;
        }
        return ResumeOutcome.CONTINUED;
    }

    /** Returns true on the first submission; later calls change nothing and return false. */
    public boolean submit(Instant now, SubmitReason reason) {
        if (now == null || reason == null) {
            throw new IllegalArgumentException("now and reason are required");
        }
        sync(now);
        return doSubmit(now, reason);
    }

    private boolean doSubmit(Instant now, SubmitReason reason) {
        if (status == AttemptStatus.SUBMITTED) {
            return false;
        }
        if (sectionEnds[sectionIndex] == null) {
            sectionEnds[sectionIndex] = now;
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
        return sectionStarts[sectionIndex].plus(sections.get(sectionIndex).duration());
    }

    private void advance(Instant at) {
        sectionEnds[sectionIndex] = at;
        sectionIndex++;
        sectionStarts[sectionIndex] = at;
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
                doSubmit(end, SubmitReason.TIME_UP);
            } else {
                advance(end);
            }
        }
    }
}
