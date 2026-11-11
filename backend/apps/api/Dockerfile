# syntax=docker/dockerfile:1
# The image of the api (REST, SSE, Flyway migrations) — phase 18, 11-infra §8. The build context is backend/,
# because Gradle needs the whole multi-project build (settings, build-logic, libs):
#   docker build -f apps/api/Dockerfile -t trakr-api .        (from backend/)
# infra/docker-compose.prod.yml builds it the same way. apps/workers and apps/collab have the same shape.

# BLOCK 1: build the jar. The JDK image has Java 25, the toolchain the build asks for, so Gradle uses it instead
# of downloading one. The cache mount keeps ~/.gradle (wrapper, dependencies) between builds without putting it
# into any layer: the second build only recompiles.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :apps:api:bootJar --no-daemon --console=plain

# BLOCK 2: split the fat jar into layers. Spring Boot's jar tools write api.jar (our classes) and lib/, sorted into
# folders by how often they change: dependencies (third-party releases), spring-boot-loader,
# snapshot-dependencies, application (our code, including libs/kernel and libs/contracts).
FROM eclipse-temurin:25-jdk AS layers
WORKDIR /layers
COPY --from=build /workspace/apps/api/build/libs/api.jar api.jar
RUN java -Djarmode=tools -jar api.jar extract --layers --destination extracted

# BLOCK 4: the runtime image — a JRE: no compiler, no Gradle, no sources
FROM eclipse-temurin:25-jre
# a user of its own: if the app is ever compromised, it isn't root inside the container
RUN groupadd --system trakr && useradd --system --gid trakr --no-create-home trakr
WORKDIR /app
# least to most often changed. Docker reuses a layer when it and every layer before it are unchanged, so a code
# change rebuilds and ships only the last COPY (a few hundred kB), not the ~100 MB of dependencies.
COPY --from=layers /layers/extracted/dependencies/ ./
COPY --from=layers /layers/extracted/spring-boot-loader/ ./
COPY --from=layers /layers/extracted/snapshot-dependencies/ ./
COPY --from=layers /layers/extracted/application/ ./

# BLOCK 3: the AOT cache (JEP 483/514/515, Java 25). A training run starts the app until its context is refreshed
# (-Dspring.context.exit=onRefresh: every bean is created, nothing is started, no port opens) and exits; the JVM
# writes the classes it loaded and linked, plus method profiles, into app.aot. At runtime -XX:AOTCache maps that
# file instead of parsing and linking those classes again. The cache only fits this exact JVM and classpath, which
# is why it is made here, in the runtime image, with the same jar paths.
# There is no Postgres, Redis or Kafka during the build: the flags stop the beans that would connect while the
# context starts, and the env vars only fill in required settings. The api needs two more things: lazy
# initialization (the context still loads and evaluates every auto-configuration, which is most of what the cache
# saves, but creates beans only when something asks for one) with scheduling off, because Boot never makes
# @Scheduled beans lazy; and Spring Modulith's outbox left out, because its event multicaster creates the registry
# up front and the registry asks the database for its type. Those few classes load normally at runtime.
RUN TRAKR_DB_URL=jdbc:postgresql://training/trakr TRAKR_DB_PASSWORD=x TRAKR_DB_OWNER_PASSWORD=x \
    S3_ACCESS_KEY=x S3_SECRET_KEY=x \
    java -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh -jar api.jar \
      --spring.profiles.active=prod \
      --spring.main.lazy-initialization=true \
      --trakr.scheduling.enabled=false \
      --spring.autoconfigure.exclude=org.springframework.modulith.events.config.EventPublicationAutoConfiguration,org.springframework.modulith.events.jdbc.JdbcEventPublicationAutoConfiguration,org.springframework.modulith.events.config.EventExternalizationAutoConfiguration,org.springframework.modulith.events.kafka.KafkaEventExternalizerConfiguration \
      --spring.flyway.enabled=false \
      --spring.jpa.hibernate.ddl-auto=none \
      --spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect \
      --spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false \
      --spring.session.data.redis.configure-action=none \
      --spring.kafka.admin.auto-create=false \
    && chmod a+r app.aot

USER trakr
EXPOSE 8080
# MaxRAMPercentage: the heap may use 75% of the container's memory limit (the JVM reads the cgroup limit; the
# default is 25%). The rest is for metaspace, thread stacks, direct buffers and the code cache.
ENTRYPOINT ["java", "-XX:AOTCache=app.aot", "-XX:MaxRAMPercentage=75", "-jar", "api.jar"]
