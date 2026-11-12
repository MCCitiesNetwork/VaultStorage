plugins {
    `java-library`
    id("vaultstorage-conventions")
    id("com.gradleup.shadow")
    id("vaultstorage-shared-relocation")
    alias(libs.plugins.run.paper)
}

dependencies {
    compileOnly(libs.paper.api)
    compileOnly(libs.bolt.bukkit)
    compileOnly(libs.bolt.common)
    compileOnly(libs.worldguard)
    compileOnly(libs.essentialsx) {
        exclude(group = "org.spigotmc", module = "spigot-api")
    }
    compileOnly(libs.floodgate.api)
    compileOnly(libs.chestshop)
    compileOnly(libs.realty.paper.api) { isTransitive = false }
    // compileOnly is on neither test classpath; tests name Paper's profile type.
    testImplementation(libs.paper.api)
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    jar {
        archiveClassifier = "unshaded"
    }

    shadowJar {
        archiveBaseName = "VaultStorage"
        archiveClassifier = ""
        mergeServiceFiles()
    }

    processResources {
        val projectVersion = version
        inputs.property("version", projectVersion)
        filesMatching(listOf("plugin.yml", "paper-plugin.yml")) {
            expand("version" to projectVersion)
        }
    }

    runServer {
        minecraftVersion(libs.versions.minecraft.get())
    }
}
