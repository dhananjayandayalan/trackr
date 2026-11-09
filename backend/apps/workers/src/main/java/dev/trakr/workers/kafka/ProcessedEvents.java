package dev.trakr.workers.kafka;

import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent consumers (04-events §6). Kafka delivers at least once, so every event may arrive again —
 * after a rebalance, a retry, a resubmitted outbox row. Each consumer group records the event ids it has
 * handled in processed_event, <em>in the same transaction</em> as the side effect: either both commit
 * (and a redelivery is skipped) or neither does (and the redelivery does the work).
 */
@Component
public class ProcessedEvents {

    private static final Duration KEEP = Duration.ofDays(7);

    private final JdbcClient jdbc;

    ProcessedEvents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Runs {@code work} unless this group handled {@code eventId} before. The INSERT … ON CONFLICT DO
     * NOTHING is the check and the claim in one statement: two concurrent deliveries can't both pass.
     *
     * @return false for a duplicate
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean runOnce(String consumerGroup, UUID eventId, Runnable work) {
        int inserted = jdbc.sql("""
                        INSERT INTO processed_event (consumer_group, event_id) VALUES (:group, :id)
                        ON CONFLICT DO NOTHING""")
                .param("group", consumerGroup)
                .param("id", eventId)
                .update();
        if (inserted == 0) {
            return false;
        }
        work.run();
        return true;
    }

    /** A redelivery older than a week can't happen (topics keep 7 days), so neither can its duplicate. */
    @Scheduled(cron = "0 15 4 * * *", zone = "UTC")
    @Transactional
    public void purge() {
        jdbc.sql("DELETE FROM processed_event WHERE processed_at < now() - make_interval(days => :days)")
                .param("days", (int) KEEP.toDays())
                .update();
    }
}
