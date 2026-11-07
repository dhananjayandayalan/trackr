// BLOCK 1: a plain Java module
plugins {
    id("trakr.java-conventions")
}

// BLOCK 2: kernel types (ids, time) are part of every contract, so consumers see them too
dependencies {
    api(project(":libs:kernel"))
    // BLOCK 3: JSON names for the event payloads (annotations only, no Jackson runtime)
    api(libs.jackson.annotations)
}
