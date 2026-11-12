plugins {
    id("vaultstorage-module-conventions")
}

dependencies {
    // Not transitive: GriefPrevention drags in the old Bukkit API, and Paper's must win.
    compileOnly(libs.griefprevention) { isTransitive = false }
}
