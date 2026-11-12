plugins {
    `java-library`
    id("vaultstorage-conventions")
    id("com.gradleup.shadow")
    id("vaultstorage-shared-relocation")
    alias(libs.plugins.run.paper)
}

dependencies {
    compileOnly(libs.paper.api)
    // Bundled and relocated; modules reach it through the host's class loader.
    implementation(libs.plugin.infrastructure)
    compileOnly(libs.bolt.bukkit)
    compileOnly(libs.bolt.common)
    compileOnly(libs.worldguard)
    compileOnly(libs.essentialsx) {
        exclude(group = "org.spigotmc", module = "spigot-api")
    }
    compileOnly(libs.realty.paper.api) { isTransitive = false }
    // compileOnly is on neither test classpath; tests name Paper's profile type.
    testImplementation(libs.paper.api)
}

// Module jars, for the run directory.
val moduleJars: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    moduleJars(project(path = ":modules:namebook-adapter", configuration = "moduleJar"))
    moduleJars(project(path = ":modules:floodgate-adapter", configuration = "moduleJar"))
    moduleJars(project(path = ":modules:chestshop-adapter", configuration = "moduleJar"))
}

val installModules by tasks.registering(Sync::class) {
    from(moduleJars)
    into(layout.projectDirectory.dir("run/plugins/VaultStorage/modules"))
    // Module configs written by a previous run survive.
    preserve { exclude("*.jar") }
}

tasks {
    runServer {
        dependsOn(installModules)
    }

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
