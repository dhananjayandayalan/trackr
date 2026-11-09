package dev.trakr.workers.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import dev.trakr.contracts.events.IssueSnapshot;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Zero-downtime reindex (05-search §6): a new index next to the live one, filled from Postgres while the
 * live consumer writes to both, then one atomic alias switch. Searches never see a half-built index.
 */
@Component
public class Reindexer {

    public record Result(String previousIndex, String index, long documents) {
    }

    private static final Logger log = LoggerFactory.getLogger(Reindexer.class);
    private static final int BULK_SIZE = 500;
    private static final Duration KEEP_OLD = Duration.ofHours(1);

    private final ElasticsearchClient client;
    private final IndexManager indices;
    private final IndexTargets targets;
    private final IssueRows rows;
    private final NameLookup names;
    private final TaskScheduler scheduler;
    // one reindex at a time: two would both pick "v{n+1}"
    private final ReentrantLock running = new ReentrantLock();

    Reindexer(ElasticsearchClient client, IndexManager indices, IndexTargets targets, IssueRows rows, NameLookup names,
            TaskScheduler scheduler) {
        this.client = client;
        this.indices = indices;
        this.targets = targets;
        this.rows = rows;
        this.names = names;
        this.scheduler = scheduler;
    }

    public Result reindex() throws IOException {
        if (!running.tryLock()) {
            throw new IllegalStateException("a reindex is already running");
        }
        try {
            // 1. a new index with the current mapping
            String previous = indices.currentIndex();
            String next = IndexManager.PREFIX + (IndexManager.versionOf(previous) + 1);
            indices.create(next);
            // 3. live writes go to both from now on — before the copy starts, so nothing falls between
            targets.startDualWrite(next);
            long documents;
            try {
                // 2. every issue from Postgres, with its version as the external version: a live write of a newer
                // version wins over the copy, whichever arrives first
                List<IssueSnapshot> buffer = new ArrayList<>(BULK_SIZE);
                documents = rows.forEach(snapshot -> {
                    buffer.add(snapshot);
                    if (buffer.size() == BULK_SIZE) {
                        flush(next, buffer);
                    }
                });
                flush(next, buffer);
                client.indices().refresh(r -> r.index(next));
                // 4. switch the alias atomically
                indices.swap(previous, next);
            } finally {
                targets.stopDualWrite();
            }
            // the old index stays an hour, in case something still reads it by name
            scheduler.schedule(() -> deleteQuietly(previous), Instant.now().plus(KEEP_OLD));
            log.info("reindexed previous={} index={} documents={}", previous, next, documents);
            return new Result(previous, next, documents);
        } finally {
            running.unlock();
        }
    }

    private void flush(String index, List<IssueSnapshot> buffer) {
        if (buffer.isEmpty()) {
            return;
        }
        Map<UUID, IssueContext> contexts = names.contextsFor(buffer);
        List<BulkOperation> operations = buffer.stream()
                .map(snapshot -> SearchIndexer.indexOperation(index, snapshot, contexts.get(snapshot.id())))
                .toList();
        try {
            SearchIndexer.check(client.bulk(b -> b.operations(operations)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        buffer.clear();
    }

    private void deleteQuietly(String index) {
        try {
            indices.delete(index);
        } catch (IOException | RuntimeException e) {
            log.warn("could not delete old index index={} error={}", index, e.toString());
        }
    }
}
