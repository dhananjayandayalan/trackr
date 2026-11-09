package dev.trakr.workers.search;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import dev.trakr.workers.Fixtures;
import dev.trakr.workers.Fixtures.World;
import dev.trakr.workers.WorkersIT;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Postgres is the source of truth: wipe the index, reindex, and it holds exactly the live issues again. */
@WorkersIT
class ReindexIT {

    private final Reindexer reindexer;
    private final IndexManager indices;
    private final ElasticsearchClient elasticsearch;
    private final JdbcClient jdbc;

    @Autowired
    ReindexIT(Reindexer reindexer, IndexManager indices, ElasticsearchClient elasticsearch, JdbcClient jdbc) {
        this.reindexer = reindexer;
        this.indices = indices;
        this.elasticsearch = elasticsearch;
        this.jdbc = jdbc;
    }

    @Test
    void reindexRebuildsTheIndexFromPostgresAndSwitchesTheAlias() throws IOException {
        World w = Fixtures.world(jdbc);
        for (int n = 1; n <= 25; n++) {
            Fixtures.issue(jdbc, w, n, "Issue " + n, null, n % 2 == 0);
        }
        // a deleted issue stays out of the index
        jdbc.sql("UPDATE issue SET deleted_at = now() WHERE project_id = ? AND number = 25").params(w.project()).update();
        String before = indices.currentIndex();

        // wipe: the index no longer matches Postgres
        elasticsearch.deleteByQuery(d -> d.index(IndexManager.ALIAS).query(q -> q.matchAll(m -> m)).refresh(true));
        assertThat(count()).isZero();

        Reindexer.Result result = reindexer.reindex();

        long live = jdbc.sql("SELECT count(*) FROM issue WHERE deleted_at IS NULL").query(Long.class).single();
        assertThat(result.documents()).isEqualTo(live);
        assertThat(result.previousIndex()).isEqualTo(before);
        assertThat(indices.currentIndex()).isEqualTo(result.index()).isNotEqualTo(before);
        assertThat(count()).isEqualTo(live);
        long ours = elasticsearch.count(c -> c.index(IndexManager.ALIAS)
                .query(q -> q.term(t -> t.field("projectId").value(w.project().toString())))).count();
        assertThat(ours).isEqualTo(24);
    }

    private long count() throws IOException {
        elasticsearch.indices().refresh(r -> r.index(IndexManager.ALIAS));
        return elasticsearch.count(c -> c.index(IndexManager.ALIAS)).count();
    }
}
