// BLOCK 1: a plain Java module
plugins {
    id("trakr.java-conventions")
}

// BLOCK 2: kernel types (ids, time) are part of every contract, so consumers see them too
dependencies {
    api(project(":libs:kernel"))
}
