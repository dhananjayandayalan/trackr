// BLOCK 1: a Spring app is a Java module plus the Boot plugin
plugins {
    id("trakr.java-conventions")
    id("org.springframework.boot")
}

// BLOCK 2: build-info.properties for /actuator/info
springBoot {
    buildInfo {
        // a timestamp changes every build and would defeat UP-TO-DATE / FROM-CACHE
        excludes = setOf("time")
    }
}

val libs = the<VersionCatalogsExtension>().named("libs")

// BLOCK 3: app-only dependencies
dependencies {
    implementation(platform(libs.findLibrary("spring-modulith-bom").get()))
    // no spring-boot-docker-compose: infra is started by hand and connections live in application.yml
}

// BLOCK 4: a stable jar name (api.jar) for Dockerfiles and scripts
tasks.bootJar {
    archiveFileName = "${project.name}.jar"
}

// BLOCK 5: run locally in UTC; postgres:18 rejects legacy zone names some machines report (Asia/Calcutta)
tasks.bootRun {
    systemProperty("user.timezone", "UTC")
}
