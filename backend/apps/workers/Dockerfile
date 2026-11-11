# syntax=docker/dockerfile:1
# The image of the workers (Kafka consumers: search index, notifications, reports, email).
# Phase 18, 11-infra §8. The build context is backend/, because Gradle needs the whole multi-project build:
#   docker build -f apps/workers/Dockerfile -t trakr-workers .        (from backend/)
# infra/docker-compose.prod.yml builds it the same way. apps/api has the same shape, with comments.

# BLOCK 1: build the jar. The JDK image has Java 25, the toolchain the build asks for, so Gradle uses it instead
# of downloading one. The cache mount keeps ~/.gradle (wrapper, dependencies) between builds without putting it
# into any layer: the second build only recompiles.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :apps:workers:bootJar --no-daemon --console=plain

# BLOCK 2: split the fat jar into layers. Spring Boot's jar tools write workers.jar (our classes) and lib/, sorted into
# folders by how often they change: dependencies (third-party releases), spring-boot-loader,
# snapshot-dependencies, application (our code, including libs/kernel and libs/contracts).
FROM eclipse-temurin:25-jdk AS layers
WORKDIR /layers
COPY --from=build /workspace/apps/workers/build/libs/workers.jar workers.jar
RUN java -Djarmode=tools -jar workers.jar extract --layers --destination extracted

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
# There is no Postgres, Redis, Kafka or Elasticsearch during the build:
# the flags stop the beans that would connect while the context starts; the env vars fill in required settings.
RUN TRAKR_DB_URL=jdbc:postgresql://training/trakr TRAKR_DB_PASSWORD=x TRAKR_ADMIN_TOKEN=x \
    java -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh -jar workers.jar \
      --spring.profiles.active=prod \
      --spring.kafka.admin.auto-create=false \
      --trakr.search.ensure-index-on-startup=false \
    && chmod a+r app.aot

USER trakr
EXPOSE 8082
# MaxRAMPercentage: the heap may use 75% of the container's memory limit (the JVM reads the cgroup limit; the
# default is 25%). The rest is for metaspace, thread stacks, direct buffers and the code cache.
ENTRYPOINT ["java", "-XX:AOTCache=app.aot", "-XX:MaxRAMPercentage=75", "-jar", "workers.jar"]
