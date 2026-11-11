import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * A build service with no behaviour: it exists only so that tasks can share its `maxParallelUsages`.
 * Every integrationTest task uses it, so with `org.gradle.parallel=true` they still run one at a time
 * and never have two modules' Testcontainers stacks (Postgres, Kafka, Redis, Elasticsearch, SeaweedFS)
 * in Docker at once.
 */
abstract class TestcontainersSlot : BuildService<BuildServiceParameters.None>
