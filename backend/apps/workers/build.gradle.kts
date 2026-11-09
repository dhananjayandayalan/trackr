// BLOCK 1: the workers app — Kafka consumers that build read models and send notifications (01-architecture §2)
plugins {
    id("trakr.spring-app")
}

dependencies {
    // BLOCK 2: our libs — the event contracts it consumes
    implementation(project(":libs:contracts"))

    // BLOCK 3: Kafka in and out, Postgres through plain JDBC (the api owns the schema), Redis for mail digests
    implementation(libs.spring.boot.starter.kafka)
    implementation(libs.spring.boot.starter.jdbc)
    runtimeOnly(libs.postgresql)
    implementation(libs.spring.boot.starter.data.redis)

    // BLOCK 4: Elasticsearch — Boot auto-configures the ElasticsearchClient from spring.elasticsearch.uris
    implementation(libs.spring.boot.starter.elasticsearch)
    implementation(libs.elasticsearch.java)
    implementation(libs.caffeine)

    // BLOCK 5: email, the /admin endpoints, health and metrics
    implementation(libs.spring.boot.starter.mail)
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)

    testImplementation(libs.spring.boot.starter.test)

    // BLOCK 6: integration tests — real Postgres, Kafka, Elasticsearch, Redis; Flyway only to create the api's schema
    integrationTestImplementation(platform(libs.testcontainers.bom))
    integrationTestImplementation(libs.spring.boot.testcontainers)
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.testcontainers.kafka)
    integrationTestImplementation(libs.testcontainers.elasticsearch)
    integrationTestImplementation(libs.testcontainers.junit.jupiter)
    integrationTestImplementation(libs.spring.boot.starter.flyway)
    integrationTestImplementation(libs.flyway.database.postgresql)
}

// BLOCK 7: the api's migrations are the schema; workers' tests apply them from the api project, never copies
tasks.named<Test>("integrationTest") {
    val migrations = project(":apps:api").layout.projectDirectory.dir("src/main/resources/db/migration")
    inputs.dir(migrations).withPropertyName("apiMigrations")
    systemProperty("trakr.api.migrations", migrations.asFile.absolutePath)
}
