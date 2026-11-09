// BLOCK 1: where plugins come from
pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// BLOCK 3: toolchain resolver
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// BLOCK 2: where dependencies come from
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

// BLOCK 4: the projects
rootProject.name = "trakr"
include("libs:kernel", "libs:contracts", "apps:api", "apps:workers")