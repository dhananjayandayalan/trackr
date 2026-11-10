package dev.trakr.api.issue.internal;

import dev.trakr.api.issue.IssueService;
import dev.trakr.contracts.events.CollabEvent.CollabSnapshot;
import dev.trakr.contracts.events.EventEnvelope;
import dev.trakr.contracts.events.EventTypes;
import dev.trakr.contracts.events.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Collab → the issue's stored description (06-collab §4 step 5, 04-events §5). The collab app keeps the live
 * document; whenever a client hands it a snapshot, the text and HTML come here, so the issue page, search and
 * emails see what was typed.
 *
 * <p>One fixed group ({@code api-collab-snapshots}): the api instances share the work, each snapshot is applied
 * once. Compare RealtimeConsumer, a group of one per instance. {@code earliest}: snapshots taken while the api was
 * down are still on the topic (kept for an hour) and are applied when it comes back.
 *
 * <p>The topic also carries the collab instances' relay traffic (every keystroke); this listener skips everything
 * but snapshots. At least once: a redelivered snapshot finds the description already equal and changes nothing.
 */
@Component
class DescriptionSnapshotConsumer {

    private static final Logger log = LoggerFactory.getLogger(DescriptionSnapshotConsumer.class);

    private final IssueService issues;
    private final JsonMapper json;

    DescriptionSnapshotConsumer(IssueService issues, JsonMapper json) {
        this.issues = issues;
        this.json = json;
    }

    @KafkaListener(id = "collab-snapshots", topics = Topics.COLLAB, groupId = "api-collab-snapshots",
            properties = "auto.offset.reset=earliest")
    void on(EventEnvelope<Object> envelope) {
        if (!EventTypes.COLLAB_SNAPSHOT.equals(envelope.type())) {
            return;
        }
        // the default deserializer gives data as a JSON map (the envelope is generic); turn it into the record
        CollabSnapshot snapshot = json.convertValue(envelope.data(), CollabSnapshot.class);
        issues.applyDescriptionSnapshot(snapshot.issueId(), snapshot.actorId(), snapshot.html());
        log.debug("description snapshot applied issue={} upto={}", snapshot.issueId(), snapshot.uptoUpdateId());
    }
}
