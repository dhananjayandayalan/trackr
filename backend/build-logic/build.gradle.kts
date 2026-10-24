// BLOCK 1: turn src/main/kotlin/*.gradle.kts into plugins
plugins {
    `kotlin-dsl`
}

// BLOCK 2: plugins the convention plugins apply must be on this build's classpath
dependencies {
    implementation(libs.spring.boot.gradle.plugin)
}
