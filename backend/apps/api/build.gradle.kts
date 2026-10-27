// BLOCK 1: the API app; its main class arrives in phase 03
plugins {
    id("trakr.spring-app")
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
}