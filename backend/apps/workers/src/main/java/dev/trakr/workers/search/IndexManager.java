package dev.trakr.workers.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The index behind the alias. Apps read and write {@code trakr-issues} (the alias), never
 * {@code trakr-issues-v1}: a new mapping means a new index, filled while the old one keeps serving, then
 * one atomic alias switch (05-search §6) — and nothing that talks to Elasticsearch changes or restarts.
 *
 * <p>Runs before the Kafka listeners start (InitializingBean, during bean creation): a document written
 * to a name that doesn't exist yet would make Elasticsearch create a concrete index with a guessed mapping.
 */
@Component
public class IndexManager implements InitializingBean {

    public static final String ALIAS = "trakr-issues";
    static final String PREFIX = "trakr-issues-v";
    private static final String MAPPING = "es/trakr-issues-v1.json";
    private static final Logger log = LoggerFactory.getLogger(IndexManager.class);

    private final ElasticsearchClient client;

    IndexManager(ElasticsearchClient client) {
        this.client = client;
    }

    @Override
    public void afterPropertiesSet() throws IOException {
        if (client.indices().existsAlias(a -> a.name(ALIAS)).value()) {
            return;
        }
        String first = PREFIX + 1;
        if (!client.indices().exists(e -> e.index(first)).value()) {
            create(first);
        }
        client.indices().updateAliases(u -> u.actions(a -> a.add(add -> add.index(first).alias(ALIAS))));
        log.info("search index created index={} alias={}", first, ALIAS);
    }

    /** A new, empty index with the current settings and mapping. */
    public void create(String index) {
        try (InputStream mapping = new ClassPathResource(MAPPING).getInputStream()) {
            client.indices().create(c -> c.index(index).withJson(mapping));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The concrete index the alias points at now. */
    public String currentIndex() throws IOException {
        return client.indices().getAlias(g -> g.name(ALIAS)).aliases().keySet().iterator().next();
    }

    /** Both actions in one request: there is no moment when the alias points at no index, or at two. */
    public void swap(String from, String to) throws IOException {
        client.indices().updateAliases(u -> u
                .actions(a -> a.remove(r -> r.index(from).alias(ALIAS)))
                .actions(a -> a.add(add -> add.index(to).alias(ALIAS))));
    }

    public void delete(String index) throws IOException {
        client.indices().delete(d -> d.index(index));
    }

    static int versionOf(String index) {
        return Integer.parseInt(index.substring(PREFIX.length()));
    }
}
