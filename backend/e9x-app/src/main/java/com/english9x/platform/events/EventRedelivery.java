package com.english9x.platform.events;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Spec §6: module events are delivered at least once. Spring Modulith keeps a publication row (event_publication) per listener until the
 * listener completes; start-up resubmits the incomplete ones (spring.modulith.events.republish-outstanding-events-on-restart), and this
 * resubmits those a failed or interrupted listener left behind while the application keeps running. Only publications older than
 * {@code e9x.events.redelivery-after} are touched, so a listener that is merely slow is not run twice at once. Consumers are idempotent
 * (see docs/superpowers/design/events.md): a resubmission may repeat work that had in fact finished.
 */
@Component
class EventRedelivery {

    private static final Logger LOG = LoggerFactory.getLogger(EventRedelivery.class);

    private final IncompleteEventPublications incomplete;
    private final Duration olderThan;

    EventRedelivery(IncompleteEventPublications incomplete, @Value("${e9x.events.redelivery-after:2m}") Duration olderThan) {
        this.incomplete = incomplete;
        this.olderThan = olderThan;
    }

    @Scheduled(initialDelayString = "${e9x.events.redelivery-after:2m}", fixedDelayString = "${e9x.events.redelivery-after:2m}")
    void resubmit() {
        try {
            incomplete.resubmitIncompletePublicationsOlderThan(olderThan);
        } catch (RuntimeException e) {
            LOG.warn("resubmitting incomplete event publications failed: {}", e.getClass().getSimpleName());
        }
    }
}
