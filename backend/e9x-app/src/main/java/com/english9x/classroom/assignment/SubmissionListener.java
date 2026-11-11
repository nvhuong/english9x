package com.english9x.classroom.assignment;

import com.english9x.exam.api.events.AttemptSubmitted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * An exam attempt was submitted: if it is of a test given to a class of the person's organisation, the assignment is handed in, on time or LATE by the
 * submission time of the attempt ROW. The event is a hint (the request role can forge one), so the definer function reads the attempt back and agrees to
 * nothing it does not find; handing the same assignment in twice keeps the first. Runs after the exam's transaction, on a pool thread with no user bound.
 */
@Component
class SubmissionListener {

    private static final Logger LOG = LoggerFactory.getLogger(SubmissionListener.class);

    private final JdbcTemplate jdbc;

    SubmissionListener(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @ApplicationModuleListener
    void on(AttemptSubmitted e) {
        Integer marked = jdbc.queryForObject("select classroom_record_submission(?, ?, ?)", Integer.class, e.attemptId(), e.userId(), e.testVersionId());
        if (marked != null && marked > 0) {
            LOG.info("attempt {} handed in {} assignment(s)", e.attemptId(), marked);
        }
    }
}
