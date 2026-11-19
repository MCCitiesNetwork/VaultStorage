import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile
import me.drownek.plugwright.local.LocalMode

plugins {
    `java-library`
    id("vaultstorage-conventions")
    id("com.gradleup.shadow")
    id("vaultstorage-shared-relocation")
    alias(libs.plugins.run.paper)
    alias(libs.plugins.plugwright)
}

dependencies {
    compileOnly(libs.paper.api)
    // Bundled and relocated; modules reach it through the host's class loader.
    implementation(libs.plugin.infrastructure)
    implementation(libs.configurate.yaml)
    implementation(libs.mybatis)
    implementation(libs.mariadb.java.client)
    compileOnly(libs.bolt.bukkit)
    compileOnly(libs.bolt.common)
    compileOnly(libs.worldguard)
    // compileOnly is on neither test classpath; tests name Paper's profile type.
    testImplementation(libs.paper.api)
    // Database tests run against the real engine and need a running Docker daemon.
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.junit.jupiter)
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
    moduleJars(project(path = ":modules:realty-autovault", configuration = "moduleJar"))
    moduleJars(project(path = ":modules:griefprevention-adapter", configuration = "moduleJar"))
    moduleJars(project(path = ":modules:player-notifications-adapter", configuration = "moduleJar"))
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
        // The plugins the host requires. Optional ones are added by hand when a module is tested.
        downloadPlugins {
            // By version ID: the version number alone resolves to the Fabric jar.
            modrinth("worldedit", "YFnqiUlL") // 7.4.6, bukkit
            modrinth("worldguard", "7.0.20")
            modrinth("bolt", "1.2.37")
        }
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

// ---------------------------------------------------------------------------------------------
// Live-server tests: ./gradlew :plugin:plugwrightTest. A separate task, never wired into test,
// check or build: it starts a database and a Paper server and drives them with bots.
// ---------------------------------------------------------------------------------------------

val e2eRunDir = layout.projectDirectory.dir("src/test/e2e/generated/local/run")

plugwright {
    testsDir.set(file("src/test/e2e"))

    environments {
        create("local", LocalMode) {
            minecraftVersion.set(libs.versions.minecraft.get())
            // Records agreement to the Minecraft EULA on behalf of whoever runs the tests.
            acceptEula.set(true)
            // Ports of its own, so these tests can run while another project's test server is up.
            port.set(25620)
            rconPort.set(25621)
            writeFiles {
                // Paper 26.3 starts with its whitelist on, and the bots are not on it. A flat
                // world, so every test knows what is under it.
                file("server.properties", """
                    white-list=false
                    level-type=minecraft\:flat
                    spawn-protection=0
                """.trimIndent() + "\n")
                // The dev database from compose.dev.yml.
                file("plugins/VaultStorage/config.yml", """
                    mysql:
                      host: "127.0.0.1"
                      port: 3309
                      database: "vault_storage"
                      user: "vaultstorage"
                      password: "vaultstorage"
                      useSSL: false
                    auto-vault:
                      enabled: true
                      delay-ticks: 20
                      include-hangings: true
                      notify-occupant: true
                      notify-message: "<yellow>Cleared.</yellow>"
                """.trimIndent() + "\n")
            }

            downloadPlugins {
                // What the host requires.
                url("https://cdn.modrinth.com/data/1u6JkXh5/versions/YFnqiUlL/worldedit-bukkit-7.4.6.jar")
                url("https://cdn.modrinth.com/data/DKY9btbd/versions/9YdeGXiK/worldguard-bukkit-7.0.20.jar")
                url("https://cdn.modrinth.com/data/YqbUofZE/versions/oWPMxWDV/Bolt-1.2.37.jar")
                // For the tests of what a player who is not an operator may do.
                url("https://cdn.modrinth.com/data/Vebnzrzj/versions/b0mk8uS6/LuckPerms-Bukkit-5.5.71.jar")
                // GriefPrevention 16.18.8, the release for 26.3, for the placement grant.
                url("https://cdn.modrinth.com/data/O4o4mKaq/versions/MtwPHMAS/GriefPrevention.jar")
                // The bots join as 1.21.11 clients; these let them join a 26.3 server.
                // See src/test/e2e/plugins/test-server.ts.
                url("https://cdn.modrinth.com/data/P1OZGk5p/versions/Fkfcr89K/ViaVersion-5.12.2.jar")
                url("https://cdn.modrinth.com/data/NpvuJQoq/versions/WFO37e8T/ViaBackwards-5.12.2.jar")
            }
            plugins { local("test-server") }
        }
    }
}

val startE2eDatabase by tasks.registering(Exec::class) {
    description = "Starts the MariaDB from compose.dev.yml for the live-server tests."
    commandLine("docker", "compose", "-f", rootProject.file("compose.dev.yml").absolutePath, "up", "-d", "--wait")
}

// Plugwright installs the host jar only; the modules go where the host looks for them.
val installE2eModules by tasks.registering(Sync::class) {
    from(moduleJars)
    into(e2eRunDir.dir("plugins/VaultStorage/modules"))
    preserve { exclude("*.jar") }
}

// ChestShop and Treasury are not published anywhere: they come from a checkout of hibernia-economy
// beside this repository (or wherever -PhiberniaEconomyDir points), built with its
// `./gradlew release`. Without them the ChestShop specs skip themselves.
val hiberniaEconomyDir = providers.gradleProperty("hiberniaEconomyDir").orElse("../hibernia-economy")
    .map { rootProject.file(it) }

val installE2eEconomy by tasks.registering {
    description = "Installs the economy plugins from a hibernia-economy checkout into the test server."
    val releaseDir = hiberniaEconomyDir.map { File(it, "release") }
    val pluginsDir = e2eRunDir.dir("plugins").asFile
    doLast {
        // Treasury refuses to start unless all four economy plugins are there, at one version.
        val dataFolders = mapOf("treasury" to "Treasury", "business" to "Business",
            "chestshop" to "ChestShop", "treasury-api-plugin" to "TreasuryAPI")
        val installed = Regex("(treasury|business|chestshop|treasury-api-plugin)-\\d.*\\.jar")
        pluginsDir.listFiles { file -> installed.matches(file.name) }?.forEach { it.delete() }
        val jars = releaseDir.get().listFiles { file -> installed.matches(file.name) }?.toList().orEmpty()
        if (jars.size < dataFolders.size) {
            logger.lifecycle("No full economy release in ${releaseDir.get()}; the ChestShop specs will skip.")
            return@doLast
        }
        for (jar in jars) {
            jar.copyTo(File(pluginsDir, jar.name), overwrite = true)
            // Each plugin's own default config, pointed at the dev database. They refuse to
            // start on their placeholder passwords, and Treasury creates the schema itself.
            val shipped = ZipFile(jar).use { zip ->
                zip.getInputStream(zip.getEntry("config.yml")).readBytes().toString(Charsets.UTF_8)
            }
            if (!shipped.contains(Regex("(?m)^database:"))) {
                continue
            }
            val config = shipped
                .replaceFirst(Regex("(?m)^(  host:).*$"), "$1 127.0.0.1")
                .replaceFirst(Regex("(?m)^(  port:).*$"), "$1 3309")
                .replaceFirst(Regex("(?m)^(  username:).*$"), "$1 root")
                .replaceFirst(Regex("(?m)^(  password:).*$"), "$1 root")
                .replaceFirst(Regex("(?m)^(  jwt-secret:).*$"), "$1 live-test-secret-of-more-than-thirty-two-bytes")
            val folder = dataFolders.getValue(installed.matchEntire(jar.name)!!.groupValues[1])
            File(pluginsDir, "$folder/config.yml").apply { parentFile.mkdirs() }.writeText(config)
        }

        val created = ProcessBuilder("docker", "exec", "vaultstorage-dev-mariadb", "mariadb", "-uroot", "-proot",
            "-e", "CREATE DATABASE IF NOT EXISTS treasury").inheritIO().start().waitFor()
        check(created == 0) { "Could not create the treasury database in the dev MariaDB" }
    }
}

// plugwright 3.0.0 installs Paper 26.3 build 3, an alpha whose spawn finder crashes the server
// when several bots join at once. Swap in a stable build after plugwright provisions the server
// and before any test runs. Remove this once plugwright installs the latest stable build.
val paperStableServerSha256 = "9601f9a26a2a52a0f2c807cf1abf691f96ee4fb287878313b601cebc2c51eabf"
val paperStableServerUrl = "https://fill-data.papermc.io/v1/objects/$paperStableServerSha256/paper-26.3-244.jar"

val usePaperStableBuild by tasks.registering {
    description = "Replaces the plugwright test server jar with Paper 26.3 build 244."
    val serverJar = e2eRunDir.file("server.jar").asFile
    val url = paperStableServerUrl
    val expected = paperStableServerSha256
    doLast {
        fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        if (serverJar.exists() && sha256(serverJar) == expected) {
            return@doLast
        }
        val download = File(serverJar.parentFile, "server.jar.download")
        URI(url).toURL().openStream().use { input ->
            download.outputStream().use { input.copyTo(it) }
        }
        check(sha256(download) == expected) { "Paper download failed its checksum" }
        download.copyTo(serverJar, overwrite = true)
        download.delete()
    }
}

// Plugwright registers its tasks late, so they are matched by name instead of tasks.named(...).
tasks.matching { it.name == "plugwrightProvisionLocal" }.configureEach {
    dependsOn(startE2eDatabase)
    finalizedBy(usePaperStableBuild, installE2eModules, installE2eEconomy)
}
tasks.matching { it.name == "plugwrightTestLocal" || it.name == "plugwrightRunServerLocal" }.configureEach {
    dependsOn(usePaperStableBuild, installE2eModules, installE2eEconomy)
}
listOf(usePaperStableBuild, installE2eModules, installE2eEconomy).forEach { task ->
    task.configure { mustRunAfter(tasks.matching { it.name == "plugwrightProvisionLocal" }) }
}
