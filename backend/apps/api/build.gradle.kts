// BLOCK 1: the API app; its main class arrives in phase 03
plugins {
    id("trakr.spring-app")
    // BLOCK 7: jOOQ codegen plugin
    alias(libs.plugins.jooq.codegen)
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
    testImplementation(libs.spring.modulith.docs)

    // BLOCK 10: integration tests run against a real Postgres in a container
    integrationTestImplementation(platform(libs.testcontainers.bom))
    integrationTestImplementation(libs.spring.boot.testcontainers)
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.testcontainers.junit.jupiter)
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
