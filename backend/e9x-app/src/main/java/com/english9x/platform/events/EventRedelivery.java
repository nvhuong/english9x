package com.english9x.platform.events;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Spec §6: module events are delivered at least once. This is the ONLY redelivery path (spring.modulith.events.republish-outstanding-events-on-restart
 * is false, so a publication that can no longer be read cannot stop the application from starting): the first cycle runs
 * {@code e9x.events.redelivery-after} after start-up and resubmits the publications a failed or interrupted listener left behind. Only
 * publications older than that are touched, so a listener that is merely slow is not run twice at once. Consumers are idempotent
 * (see docs/superpowers/design/events.md): a resubmission may repeat work that had in fact finished.
 *
 * <p>Each publication is handled on its own. One that cannot be read back (the record changed shape, the class is gone, the row was
 * damaged) is logged as an error with its id, event type and listener (never the payload) and skipped; the others are delivered in the
 * same cycle. One that has not completed within {@code e9x.events.publication-max-age} is logged as an error and deleted, so a failing
 * row cannot keep personal data for ever. Every cycle also warns with the number of rows older than twice the redelivery age.
 */
@Component
class EventRedelivery {

    private static final Logger LOG = LoggerFactory.getLogger(EventRedelivery.class);

    private final IncompleteEventPublications incomplete;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration olderThan;
    private final Duration maxAge;

    EventRedelivery(IncompleteEventPublications incomplete, JdbcTemplate jdbc, Clock clock,
                    @Value("${e9x.events.redelivery-after:2m}") Duration olderThan,
                    @Value("${e9x.events.publication-max-age:30d}") Duration maxAge) {
        this.incomplete = incomplete;
        this.jdbc = jdbc;
        this.clock = clock;
        this.olderThan = olderThan;
        this.maxAge = maxAge;
    }

    @Scheduled(initialDelayString = "${e9x.events.redelivery-after:2m}", fixedDelayString = "${e9x.events.redelivery-after:2m}")
    void resubmit() {
        step("deleting expired event publications", this::deleteExpired);
        step("counting stale event publications", this::warnStale);
        step("resubmitting incomplete event publications", () -> incomplete.resubmitIncompletePublications(this::deliverable));
    }

    private static void step(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            LOG.warn("{} failed: {}", what, e.getClass().getSimpleName());
        }
    }

    /** The registry asks per publication; a row that cannot be read back is skipped, not allowed to abort the batch. */
    private boolean deliverable(EventPublication publication) {
        if (publication.getPublicationDate().isAfter(clock.instant().minus(olderThan))) {
            return false;
        }
        try {
            publication.getEvent();
            return true;
        } catch (RuntimeException e) {
            logUnreadable(publication.getIdentifier(), e);
            return false;
        }
    }

    private void logUnreadable(UUID id, RuntimeException e) {
        // the class only: a deserialization message quotes the payload
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String[] row = describe(id);
        LOG.error("event publication {} (type {}, listener {}) cannot be read back and is skipped: {}", id, row[0], row[1],
                root.getClass().getSimpleName());
    }

    private String[] describe(UUID id) {
        try {
            List<String[]> rows = jdbc.query("select event_type, listener_id from event_publication where id = ?",
                    (rs, i) -> new String[] {rs.getString(1), rs.getString(2)}, id);
            return rows.isEmpty() ? new String[] {"?", "?"} : rows.get(0);
        } catch (RuntimeException e) {
            return new String[] {"?", "?"};
        }
    }

    private void deleteExpired() {
        Timestamp cutoff = Timestamp.from(clock.instant().minus(maxAge));
        List<Object[]> expired = jdbc.query("select id, event_type, listener_id from event_publication "
                + "where completion_date is null and publication_date < ?",
                (rs, i) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)}, cutoff);
        for (Object[] row : expired) {
            int gone = jdbc.update("delete from event_publication where id = ? and completion_date is null", row[0]);
            if (gone == 1) {
                LOG.error("event publication {} (type {}, listener {}) never completed within {} and is deleted", row[0], row[1], row[2], maxAge);
            }
        }
    }

    private void warnStale() {
        Instant cutoff = clock.instant().minus(olderThan.multipliedBy(2));
        Integer stale = jdbc.queryForObject("select count(*) from event_publication where completion_date is null and publication_date < ?",
                Integer.class, Timestamp.from(cutoff));
        if (stale != null && stale > 0) {
            LOG.warn("{} event publications are older than {} and have not completed", stale, olderThan.multipliedBy(2));
        }
    }
}
