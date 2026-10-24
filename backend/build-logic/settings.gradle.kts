// BLOCK 1: reuse the main version catalog
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

// BLOCK 2: where build-logic's own dependencies come from
dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// BLOCK 3: the build's name
rootProject.name = "build-logic"