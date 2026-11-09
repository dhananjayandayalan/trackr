// BLOCK 1: the API app; its main class arrives in phase 03
plugins {
    id("trakr.spring-app")
    // BLOCK 7: jOOQ codegen plugin
    alias(libs.plugins.jooq.codegen)
    // BLOCK 15: TQL — generates the lexer and parser from src/main/antlr/**/*.g4
    antlr
}

dependencies {
    // BLOCK 2: web + ops
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)

    // BLOCK 3: our libs
    implementation(project(":libs:contracts"))

    // BLOCK 4: tests
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)

    // BLOCK 5: configuration metadata
    annotationProcessor(platform(libs.spring.boot.dependencies))
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    // BLOCK 6: persistence dependencies
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.jooq)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)

    // BLOCK 7: jOOQ codegen plugin
    jooqCodegen(libs.jooq.meta.extensions)

    // BLOCK 11: identity — security, OAuth2 login, sessions in Redis, mail
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.security.oauth2.client)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.session.data.redis)
    implementation(libs.spring.boot.starter.mail)
    runtimeOnly(libs.bcprov)
    testImplementation(libs.spring.boot.starter.security.test)

    // BLOCK 12: application modules and the OpenAPI document
    implementation(libs.spring.modulith.starter.core)
    implementation(libs.springdoc.openapi.starter.webmvc.ui)
    testImplementation(libs.spring.modulith.starter.test)

    // BLOCK 14: events — outbox in Postgres (event_publication), externalized to Kafka
    implementation(libs.spring.modulith.starter.jdbc)
    implementation(libs.spring.modulith.events.kafka)
    implementation(libs.spring.boot.starter.kafka)
    testImplementation(libs.spring.modulith.docs)

    // BLOCK 10: integration tests run against a real Postgres in a container
    integrationTestImplementation(platform(libs.testcontainers.bom))
    integrationTestImplementation(libs.spring.boot.testcontainers)
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.testcontainers.junit.jupiter)
    integrationTestImplementation(libs.testcontainers.kafka)

    // BLOCK 15: search — the TQL parser (tool at build time, runtime in the jar) and the Elasticsearch client
    antlr(libs.antlr.tool)
    implementation(libs.antlr.runtime)
    implementation(libs.spring.boot.starter.elasticsearch)
    implementation(libs.elasticsearch.java)
    integrationTestImplementation(libs.testcontainers.elasticsearch)
}

// BLOCK 15: the antlr plugin makes `api` extend `antlr`, which would ship the whole tool (and its own
// dependencies) inside api.jar. The generated code needs only antlr4-runtime, declared above.
configurations.api {
    setExtendsFrom(extendsFrom.filterNot { it.name == "antlr" })
}

// The .g4 sits in the package's directory, so Gradle already writes the classes there; -package adds the
// matching package declaration (the packageName property would nest the directories a second time).
// A visitor (AstBuilder extends TqlBaseVisitor); no listener, we never walk the tree that way.
tasks.generateGrammarSource {
    arguments = arguments + listOf("-package", "dev.trakr.api.search.internal.tql.grammar", "-visitor", "-no-listener")
}

// BLOCK 8: codegen configuration
jooq {
    configuration {
        generator {
            database {
                name = "org.jooq.meta.extensions.ddl.DDLDatabase"
                properties {
                    property {
                        key = "scripts"
                        value = "src/main/resources/db/migration/*.sql"
                    }
                    property {
                        key = "sort"
                        value = "flyway"
                    }
                    property {
                        key = "defaultNameCase"
                        value = "lower"
                    }
                }
            }
            generate {
                // issue.parent_id → issue references its own table: the to-many path method would clash
                // with the to-one one ("Ambiguous key name"). We never use to-many paths, so leave them out.
                withImplicitJoinPathsToMany(false)
            }
            target {
                packageName = "dev.trakr.jooq"
                directory = "build/generated-sources/jooq"
            }
        }
    }
}

// BLOCK 9: compile after codegen
sourceSets {
    main {
        java {
            srcDir("build/generated-sources/jooq")
        }
    }
}

tasks.named<org.jooq.codegen.gradle.CodegenTask>("jooqCodegen") {
    // the migrations are the only input, so codegen reruns only when the SQL changes
    inputs.files(fileTree("src/main/resources/db/migration"))
}

tasks.compileJava {
    dependsOn(tasks.named("jooqCodegen"))
}

// BLOCK 13: integration test switches — -PupdateSnapshots=true rewrites snapshot files, -Pperf=true runs @Tag("perf")
tasks.named<Test>("integrationTest") {
    val snapshots = layout.projectDirectory.dir("src/integrationTest/resources/snapshots")
    systemProperty("snapshots.dir", snapshots.asFile.absolutePath)
    systemProperty("snapshots.update", providers.gradleProperty("updateSnapshots").getOrElse("false"))
    systemProperty("perf", providers.gradleProperty("perf").getOrElse("false"))
    inputs.files(snapshots).withPropertyName("snapshots")
    // BLOCK 15: workers owns the index mapping; the search tests create their index from that same file
    val mapping = project(":apps:workers").layout.projectDirectory.file("src/main/resources/es/trakr-issues-v1.json")
    inputs.file(mapping).withPropertyName("searchMapping")
    systemProperty("trakr.search.mapping", mapping.asFile.absolutePath)
}
