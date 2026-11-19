plugins {
    id("vaultstorage-module-conventions")
}

dependencies {
    compileOnly(libs.realty.paper.api) { isTransitive = false }
    // The sweep reads Bolt protections and WorldGuard regions through the host's services,
    // whose signatures name these types.
    compileOnly(libs.bolt.bukkit)
    compileOnly(libs.bolt.common)
    compileOnly(libs.worldguard)
    // The module keeps its pending sweeps in a table of its own, through the host's MyBatis.
    compileOnly(libs.mybatis)
    // Its own config.yml, read with the host's Configurate.
    compileOnly(libs.configurate.yaml)
    testImplementation(libs.configurate.yaml)
    // SweepFilterTest builds regions, whose bounds are a Bukkit type.
    testImplementation(libs.paper.api)
    // Database tests run against the real engine and need a running Docker daemon.
    testImplementation(libs.mybatis)
    testImplementation(libs.plugin.infrastructure)
    testImplementation(libs.mariadb.java.client)
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.junit.jupiter)
}
