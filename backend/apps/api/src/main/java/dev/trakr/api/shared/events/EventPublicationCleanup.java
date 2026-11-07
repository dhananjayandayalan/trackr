package dev.trakr.api.shared.events;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Housekeeping for the outbox (04-events §2).
 *
 * <p>Completed publications: kept a week, then deleted by this job — not {@code completion-mode=delete},
 * which removes a row the moment Kafka acks it. A week of sent events in event_publication answers
 * "did we really send that?" when debugging, and costs one indexed DELETE a day.
 *
 * <p>Incomplete ones: sent again every minute once they are a minute old. Republishing at restart
 * alone would leave events waiting for the next deploy after a Kafka outage. Several instances may
 * resubmit the same row at the same time; that only adds duplicates, which consumers drop (at least
 * once, 04-events §6).
 */
@Component
class EventPublicationCleanup {

    private static final Logger log = LoggerFactory.getLogger(EventPublicationCleanup.class);
    private static final Duration KEEP_COMPLETED = Duration.ofDays(7);
    private static final Duration RESUBMIT_AFTER = Duration.ofMinutes(1);

    private final CompletedEventPublications completed;
    private final IncompleteEventPublications incomplete;

    EventPublicationCleanup(CompletedEventPublications completed, IncompleteEventPublications incomplete) {
        this.completed = completed;
        this.incomplete = incomplete;
    }

    // 03:30 every night, when nobody is dragging cards
    @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
    void deleteOldCompletedPublications() {
        completed.deletePublicationsOlderThan(KEEP_COMPLETED);
        log.info("event_publication cleanup keepDays={}", KEEP_COMPLETED.toDays());
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void resubmitStuckPublications() {
        incomplete.resubmitIncompletePublicationsOlderThan(RESUBMIT_AFTER);
    }
}
