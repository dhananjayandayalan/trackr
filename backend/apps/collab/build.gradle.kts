// BLOCK 1: the collab app — a WebSocket relay for collaborative description editing (01-architecture §2, 06-collab)
plugins {
    id("trakr.spring-app")
}

dependencies {
    // BLOCK 2: our libs — the CollabEvent records it publishes and reads on trakr.collab.v1
    implementation(project(":libs:contracts"))

    // BLOCK 3: WebSockets on Tomcat (the starter brings spring-webmvc and the embedded server), health and metrics
    implementation(libs.spring.boot.starter.websocket)
    implementation(libs.spring.boot.starter.actuator)

    // BLOCK 4: Postgres through plain JDBC — collab_update/collab_snapshot (written), issue/project tables (read)
    implementation(libs.spring.boot.starter.jdbc)
    runtimeOnly(libs.postgresql)

    // BLOCK 5: Redis — the api's sessions (read only) and the room presence used to pick a checkpoint leader.
    // Not Spring Session: collab reads one attribute of a session the api wrote; it never creates or saves one.
    implementation(libs.spring.boot.starter.data.redis)

    // BLOCK 6: Kafka — fan-out between collab instances, snapshots to the api
    implementation(libs.spring.boot.starter.kafka)

    // BLOCK 7: configuration metadata for CollabProperties
    annotationProcessor(platform(libs.spring.boot.dependencies))
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation(libs.spring.boot.starter.test)

    // BLOCK 8: integration tests — real Postgres, Redis, Kafka; Flyway only to create the api's schema
    integrationTestImplementation(platform(libs.testcontainers.bom))
    integrationTestImplementation(libs.spring.boot.testcontainers)
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.testcontainers.kafka)
    integrationTestImplementation(libs.testcontainers.junit.jupiter)
    integrationTestImplementation(libs.spring.boot.starter.flyway)
    integrationTestImplementation(libs.flyway.database.postgresql)
}

// BLOCK 9: the api's migrations are the schema; collab's tests apply them from the api project, never copies
tasks.named<Test>("integrationTest") {
    val migrations = project(":apps:api").layout.projectDirectory.dir("src/main/resources/db/migration")
    inputs.dir(migrations).withPropertyName("apiMigrations")
    systemProperty("trakr.api.migrations", migrations.asFile.absolutePath)
}

// BLOCK 10: the integration tests replay the same JavaScript-made fixtures as the unit tests (src/test/resources/fixtures)
sourceSets.named("integrationTest") {
    resources.srcDir("src/test/resources")
}
