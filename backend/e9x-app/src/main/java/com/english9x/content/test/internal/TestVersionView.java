package com.english9x.content.test.internal;

import com.english9x.catalog.api.ExamInfo;
import com.english9x.catalog.api.ExamSection;
import com.english9x.catalog.api.Exams;
import com.english9x.content.question.api.QuestionExamView;
import com.english9x.content.question.api.QuestionGroupExamView;
import com.english9x.content.question.api.QuestionGroupVersions;
import com.english9x.content.question.api.QuestionVersions;
import com.english9x.content.test.api.TestExamView;
import com.english9x.content.test.api.TestStaffView;
import com.english9x.content.test.api.TestVersions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@link TestVersions} API. {@link #forExamRoom} assembles the exam room's picture of a test from the EXAM VIEWS of the question module only
 * ({@code QuestionGroupVersions#examView}, {@code QuestionVersions#examView}), which are built from SQL functions that return the content side and nothing
 * else. So the solution side (each question's answer key with its transcript, sample answer and notes, each explanation, each group's transcript and
 * notes) is never read here, let alone copied: there is no code path from the answer columns to {@link TestExamView}.
 */
@Component
class TestVersionView implements TestVersions {

    private final TestRepository repository;
    private final QuestionGroupVersions groups;
    private final QuestionVersions questions;
    private final Exams exams;

    TestVersionView(TestRepository repository, QuestionGroupVersions groups, QuestionVersions questions, Exams exams) {
        this.repository = repository;
        this.groups = groups;
        this.questions = questions;
        this.exams = exams;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TestExamView> forExamRoom(UUID testVersionId) {
        Optional<TestStaffView> version = repository.findServable(testVersionId);
        if (version.isEmpty()) {
            return Optional.empty();
        }
        TestStaffView v = version.get();
        List<ExamSection> sections = exams.find(v.examCode()).map(ExamInfo::sections).orElse(List.of());
        List<TestExamView.Part> parts = new ArrayList<>();
        for (TestStaffView.Part p : v.parts()) {
            List<TestExamView.Group> servedGroups = new ArrayList<>();
            for (UUID groupVersionId : p.groupVersionIds()) {
                Optional<QuestionGroupExamView> group = groups.examView(groupVersionId);
                if (group.isEmpty()) {
                    return Optional.empty();
                }
                List<QuestionExamView> servedQuestions = new ArrayList<>();
                for (UUID questionVersionId : group.get().questionVersionIds()) {
                    Optional<QuestionExamView> q = questions.examView(questionVersionId);
                    if (q.isEmpty()) {
                        return Optional.empty();
                    }
                    servedQuestions.add(q.get());
                }
                servedGroups.add(new TestExamView.Group(group.get().versionId(), group.get().groupId(), group.get().content(), List.copyOf(servedQuestions)));
            }
            String skill = sections.stream().filter(s -> s.code().equals(p.sectionCode())).map(ExamSection::skill).findFirst().orElse(null);
            parts.add(new TestExamView.Part(p.position(), p.sectionCode(), skill, p.durationSec(), List.copyOf(servedGroups)));
        }
        return Optional.of(new TestExamView(v.versionId(), v.testId(), v.examCode(), v.variant(), v.title(), v.maxResumes(), List.copyOf(parts)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TestStaffView> find(UUID testVersionId) {
        return repository.find(testVersionId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> testOf(UUID testVersionId) {
        return repository.testOf(testVersionId);
    }
}
