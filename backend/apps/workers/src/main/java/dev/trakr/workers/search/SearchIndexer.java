package dev.trakr.workers.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.json.JsonData;
import dev.trakr.contracts.events.CollabEvent;
import dev.trakr.contracts.events.CommentEvent;
import dev.trakr.contracts.events.EventEnvelope;
import dev.trakr.contracts.events.IssueCreated;
import dev.trakr.contracts.events.IssueDeleted;
import dev.trakr.contracts.events.IssueDescriptionChanged;
import dev.trakr.contracts.events.IssueLinked;
import dev.trakr.contracts.events.IssueMoved;
import dev.trakr.contracts.events.IssueSnapshot;
import dev.trakr.contracts.events.IssueTransitioned;
import dev.trakr.contracts.events.IssueUpdated;
import dev.trakr.contracts.events.NotificationEvent;
import dev.trakr.contracts.events.ProjectLifecycle;
import dev.trakr.contracts.events.SprintEvent;
import dev.trakr.contracts.events.SprintSnapshot;
import dev.trakr.contracts.events.Topics;
import dev.trakr.contracts.events.TrakrEvent;
import dev.trakr.contracts.events.WorkLogged;
import dev.trakr.contracts.events.WorkflowChanged;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.log.LogAccessor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.kafka.support.KafkaUtils;
import org.springframework.kafka.support.serializer.SerializationUtils;
import org.springframework.stereotype.Component;

/**
 * Kafka → Elasticsearch (05-search §5). One poll = one bulk request; the batch is acknowledged only after
 * Elasticsearch answered, so a crash in between means the batch arrives again (at least once). Arriving
 * again is harmless: every write carries the event's sequence as an <em>external version</em>, and
 * Elasticsearch refuses one that isn't newer than what it has (409) — "newest wins" without reading first,
 * whatever order duplicates and retries come in.
 *
 * <p>Synchronous bulk per batch rather than the async BulkIngester: with the ingester the listener would
 * return — and the offsets be committed — before Elasticsearch had the documents.
 */
@Component
class SearchIndexer {

    static final String GROUP = "workers-search-indexer";
    private static final Logger log = LoggerFactory.getLogger(SearchIndexer.class);
    private static final LogAccessor LOG_ACCESSOR = new LogAccessor(SearchIndexer.class);
    private static final String APPEND_COMMENT = """
            ctx._source.commentsText = (ctx._source.commentsText + ' ' + params.text).trim();
            for (def id : params.mentioned) {
              if (!ctx._source.mentionedUserIds.contains(id)) { ctx._source.mentionedUserIds.add(id); }
            }""";

    private final ElasticsearchClient client;
    private final NameLookup names;
    private final IndexTargets targets;
    private final IssueRows rows;

    SearchIndexer(ElasticsearchClient client, NameLookup names, IndexTargets targets, IssueRows rows) {
        this.client = client;
        this.names = names;
        this.targets = targets;
        this.rows = rows;
    }

    // BLOCK 1: listener — a fixed group: the workers instances share the work, each event is indexed once
    @KafkaListener(id = "search-indexer", topics = {Topics.ISSUES, Topics.COMMENTS, Topics.PROJECTS}, groupId = GROUP,
            containerFactory = "batchFactory", properties = "max.poll.records=500")
    void on(List<ConsumerRecord<String, EventEnvelope<TrakrEvent>>> records) throws IOException {
        List<IssueSnapshot> upserts = new ArrayList<>();
        List<IssueDeleted> deletes = new ArrayList<>();
        List<CommentEvent.CommentAdded> comments = new ArrayList<>();
        Map<UUID, SprintSnapshot> sprints = new LinkedHashMap<>();
        Map<UUID, WorkflowChanged> workflows = new LinkedHashMap<>();

        for (int i = 0; i < records.size(); i++) {
            ConsumerRecord<String, EventEnvelope<TrakrEvent>> record = records.get(i);
            if (record.value() == null) {
                // not a valid envelope: name the record, and the error handler sends just that one to the DLT
                throw new BatchListenerFailedException("record is not a valid event envelope",
                        SerializationUtils.getExceptionFromHeader(record,
                                KafkaUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER, LOG_ACCESSOR), i);
            }
            // BLOCK 2: route by type — exhaustive over the sealed hierarchy: a new event type won't compile
            // until someone decides what it means for the index
            switch (record.value().data()) {
                case IssueCreated e -> upserts.add(e.snapshot());
                case IssueUpdated e -> upserts.add(e.snapshot());
                case IssueTransitioned e -> upserts.add(e.snapshot());
                case IssueMoved e -> upserts.add(e.snapshot());
                // a collab snapshot changed the description: its snapshot has the new descriptionText (phase 16)
                case IssueDescriptionChanged e -> upserts.add(e.snapshot());
                case IssueDeleted e -> deletes.add(e);
                // no snapshot, and nothing the index shows changed; the next snapshot event carries everything
                case IssueLinked e -> { }
                case WorkLogged e -> { }
                case CommentEvent.CommentAdded e -> comments.add(e);
                // phase 15 rebuilds commentsText from the comment table; edits and deletes wait for that
                case CommentEvent.CommentEdited e -> { }
                case CommentEvent.CommentDeleted e -> { }
                // the document has no project field that can change (the key is permanent)
                case ProjectLifecycle e -> { }
                case SprintEvent e -> sprints.put(e.snapshot().id(), e.snapshot());
                // status names, categories or ids changed for many issues at once (phase 17)
                case WorkflowChanged e -> workflows.put(e.projectId(), e);
                case NotificationEvent e -> { }
                // collab's relay traffic is on its own topic, which the indexer doesn't read
                case CollabEvent e -> { }
            }
        }

        // BLOCK 3: bulk with external versions
        List<BulkOperation> operations = new ArrayList<>();
        Map<UUID, IssueContext> contexts = names.contextsFor(upserts);
        for (String index : targets.current()) {
            for (IssueSnapshot snapshot : upserts) {
                operations.add(indexOperation(index, snapshot, contexts.get(snapshot.id())));
            }
            for (IssueDeleted deleted : deletes) {
                operations.add(BulkOperation.of(o -> o.delete(op -> op.index(index).id(deleted.issueId().toString())
                        .routing(deleted.projectId().toString())
                        .version(deleted.sequence()).versionType(VersionType.External))));
            }
            for (CommentEvent.CommentAdded comment : comments) {
                operations.add(BulkOperation.of(o -> o.update(op -> op.index(index).id(comment.issueId().toString())
                        .routing(comment.projectId().toString())
                        .action(a -> a.script(s -> s.source(src -> src.scriptString(APPEND_COMMENT))
                                .params("text", JsonData.of(comment.bodyText()))
                                .params("mentioned", JsonData.of(comment.mentionedUserIds().stream()
                                        .map(UUID::toString).toList())))))));
            }
        }
        if (!operations.isEmpty()) {
            check(client.bulk(b -> b.operations(operations)));
        }

        // a renamed or started sprint changes sprintName/sprintState on every document in it
        for (SprintSnapshot sprint : sprints.values()) {
            names.evictSprint(sprint.id());
            for (String index : targets.current()) {
                client.updateByQuery(u -> u.index(index)
                        .query(q -> q.term(t -> t.field("sprintId").value(sprint.id().toString())))
                        .conflicts(Conflicts.Proceed)
                        .script(s -> s.source(src -> src.scriptString(
                                        "ctx._source.sprintName = params.name; ctx._source.sprintState = params.state"))
                                .params("name", JsonData.of(sprint.name()))
                                .params("state", JsonData.of(sprint.state()))));
            }
        }
        for (WorkflowChanged workflow : workflows.values()) {
            reindexProject(workflow);
        }
        log.debug("indexed records={} upserts={} deletes={}", records.size(), upserts.size(), deletes.size());
    }

    /**
     * A workflow save changes status names and categories (and moves issues off deleted statuses) without an
     * event per issue. The project's issues are read again from Postgres and indexed with {@code external_gte}:
     * an issue whose version didn't change (a rename only) is written again at the same version, which plain
     * {@code external} would refuse as "not newer".
     */
    private void reindexProject(WorkflowChanged workflow) throws IOException {
        names.evictStatuses(workflow.statuses().stream().map(WorkflowChanged.Status::id).toList());
        names.evictStatuses(workflow.remapped().keySet());
        List<IssueSnapshot> batch = new ArrayList<>();
        rows.forEachInProject(workflow.projectId(), batch::add);
        for (int from = 0; from < batch.size(); from += 500) {
            List<IssueSnapshot> chunk = batch.subList(from, Math.min(from + 500, batch.size()));
            Map<UUID, IssueContext> contexts = names.contextsFor(chunk);
            List<BulkOperation> operations = new ArrayList<>();
            for (String index : targets.current()) {
                for (IssueSnapshot snapshot : chunk) {
                    IssueDocument document = IssueDocumentMapper.toDocument(snapshot, contexts.get(snapshot.id()));
                    operations.add(BulkOperation.of(o -> o.index(op -> op.index(index).id(snapshot.id().toString())
                            .routing(snapshot.projectId().toString())
                            .version(snapshot.version()).versionType(VersionType.ExternalGte)
                            .document(document))));
                }
            }
            if (!operations.isEmpty()) {
                check(client.bulk(b -> b.operations(operations)));
            }
        }
        log.info("reindexed project after workflow change projectId={} issues={}", workflow.projectId(), batch.size());
    }

    static BulkOperation indexOperation(String index, IssueSnapshot snapshot, IssueContext context) {
        IssueDocument document = IssueDocumentMapper.toDocument(snapshot, context);
        return BulkOperation.of(o -> o.index(op -> op.index(index).id(snapshot.id().toString())
                // routing = project: a project's documents share a shard, so a project-filtered query hits one
                .routing(snapshot.projectId().toString())
                .version(snapshot.version()).versionType(VersionType.External)
                .document(document)));
    }

    // BLOCK 4: interpret the bulk response — per item, because a bulk request "succeeds" even when items fail
    static void check(BulkResponse response) {
        if (!response.errors()) {
            return;
        }
        List<String> failures = new ArrayList<>();
        for (BulkResponseItem item : response.items()) {
            if (item.error() == null) {
                continue;
            }
            // 409: the index has this version or a newer one already — the "newest wins" we want.
            // 404 on an update: a comment on an issue not indexed yet; its snapshot event will bring it
            if (item.status() == 409 || item.status() == 404) {
                continue;
            }
            failures.add(item.id() + ": " + item.error().type() + " " + item.error().reason());
        }
        if (!failures.isEmpty()) {
            // throw: the batch is retried, then parked in the DLT; external versions make the repeats harmless
            throw new IllegalStateException("bulk indexing failed for " + failures);
        }
    }
}
