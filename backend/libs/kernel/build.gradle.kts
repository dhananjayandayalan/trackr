// BLOCK 1: everything comes from the convention plugin
plugins {
    id("trakr.java-conventions")
}

// BLOCK 2: RankTest checks the rank vectors shared with the frontend; -PupdateRankVectors=true rewrites them
val rankVectors = layout.settingsDirectory.file("../docs/spec/fixtures/rank-vectors.json")

tasks.test {
    inputs.files(rankVectors).withPropertyName("rankVectors")
    systemProperty("rank.vectors", rankVectors.asFile.absolutePath)
    systemProperty("rank.vectors.update", providers.gradleProperty("updateRankVectors").getOrElse("false"))
}
