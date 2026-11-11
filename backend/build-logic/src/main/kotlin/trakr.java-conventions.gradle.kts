import org.gradle.api.tasks.testing.logging.TestExceptionFormat

// BLOCK 1: plugins every Java module gets
plugins {
    `java-library`
    jacoco
}

// precompiled plugins get no type-safe `libs` accessor, so look the catalog up by name
val libs = the<VersionCatalogsExtension>().named("libs")

// BLOCK 2: toolchain + compiler flags
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.findVersion("java").get().requiredVersion)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing", "-Werror"))
    options.encoding = "UTF-8"
}

// BLOCK 3: shared dependencies
dependencies {
    implementation(platform(libs.findLibrary("spring-boot-dependencies").get()))
    compileOnlyApi(libs.findLibrary("jspecify").get())

    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findBundle("testing").get())
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// BLOCK 4: test task
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    // time is UTC everywhere; the JDBC driver also sends this zone to Postgres when it connects
    systemProperty("user.timezone", "UTC")
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

// BLOCK 5: reproducible archives — same inputs, byte-identical jar
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// BLOCK 6: integration test suite
// one integrationTest task at a time across all modules — see TestcontainersSlot
val testcontainersSlot = gradle.sharedServices.registerIfAbsent("testcontainers", TestcontainersSlot::class) {
    maxParallelUsages = 1
}

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter()
        }
        register<JvmTestSuite>("integrationTest") {
            dependencies {
                implementation(project())
            }
            targets.all {
                testTask.configure {
                    shouldRunAfter(tasks.named("test"))
                    usesService(testcontainersSlot)
                    // Spring caches every distinct test context (a @TestBean, @MockitoBean, extra properties or
                    // module test makes a new one) for the whole run, and each holds its own containers: ~1.5 GB
                    // with Elasticsearch. Keeping two means the least recently used one is closed, which stops
                    // its containers too. The shared default context stays cached because most classes use it.
                    systemProperty("spring.test.context.cache.maxSize", "2")
                }
            }
        }
    }
}

// BLOCK 7: wire into check
configurations.named("integrationTestImplementation") {
    extendsFrom(configurations.testImplementation.get())
}

tasks.named("check") {
    dependsOn(testing.suites.named("integrationTest"))
}
