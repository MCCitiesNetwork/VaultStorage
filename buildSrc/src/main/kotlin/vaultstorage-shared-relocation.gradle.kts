plugins {
    id("com.gradleup.shadow")
}

// Every package the host shades is relocated here and nowhere else. Modules apply this same
// convention, so their references land where the host put the classes.
val prefix = "com.minecraftcitiesnetwork.vaultstorage.libraries"

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    listOf(
        "com.minecraftcitiesnetwork.pluginInfrastructure",
        "org.spongepowered",
        "io.leangen.geantyref",
        "org.apache.ibatis",
        "org.mariadb.jdbc",
    ).forEach {
        relocate(it, "$prefix.$it")
    }
}
