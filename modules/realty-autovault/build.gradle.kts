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
}
