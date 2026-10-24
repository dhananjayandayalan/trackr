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
tasks.test {
    useJUnitPlatform()
    jvmArgs("-XX:+EnableDynamicAgentLoading")
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
