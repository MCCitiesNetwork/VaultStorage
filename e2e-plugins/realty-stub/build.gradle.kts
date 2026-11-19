plugins {
    `java-library`
    id("vaultstorage-conventions")
}

// A stand-in for Realty on the live-test server. Never shipped: it exists so that a test can
// make a region change hands, which is what starts an auto-vault sweep.

val realtyApi: Configuration by configurations.creating

dependencies {
    compileOnly(libs.paper.api)
    compileOnly(libs.worldguard)
    compileOnly(libs.realty.paper.api) { isTransitive = false }
    realtyApi(libs.realty.paper.api) { isTransitive = false }
}

tasks.named<Jar>("jar") {
    archiveBaseName = "realty-stub"
    // Realty's API classes under their own names: the auto-vault module listens for these
    // exact event classes, and reaches them through the plugin called Realty.
    from(realtyApi.map { zipTree(it) }) {
        exclude("META-INF/**", "plugin.yml", "paper-plugin.yml")
    }
}
