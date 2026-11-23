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
    moduleJars(project(path = ":modules:realty", configuration = "moduleJar"))
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

// Runs a command and gives back its exit code and output. Used for docker and gh.
fun runCommand(vararg command: String): Pair<Int, String> {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
    return process.waitFor() to output
}

val startE2eDatabase by tasks.registering(Exec::class) {
    description = "Starts the MariaDB from compose.dev.yml for the live-server tests."
    commandLine("docker", "compose", "-f", rootProject.file("compose.dev.yml").absolutePath, "up", "-d", "--wait")
}

// Bots get a random four-character name each run, and a name seen before brings its old vaults
// with it. So every run starts with no vaults. The tables do not exist before the first run.
val resetE2eData by tasks.registering {
    description = "Empties the dev database's vault tables before a live-test run."
    dependsOn(startE2eDatabase)
    doLast {
        for (table in listOf("VaultItem", "Vault", "PendingSweep")) {
            runCommand("docker", "exec", "vaultstorage-dev-mariadb", "mariadb", "-uroot", "-proot", "vault_storage",
                "-e", "DELETE FROM $table")
        }
    }
}

// Plugwright installs the host jar only; the modules go where the host looks for them.
val installE2eModules by tasks.registering(Sync::class) {
    from(moduleJars)
    into(e2eRunDir.dir("plugins/VaultStorage/modules"))
    preserve { exclude("*.jar") }
}

val e2eDownloads = layout.buildDirectory.dir("e2e-downloads")

// ChestShop, Treasury, Business and TreasuryAPI are not in any Maven repository. They come from,
// in this order: -PhiberniaEconomyDir=<checkout>, a hibernia-economy checkout beside this
// repository (its `./gradlew release` output), or the rolling dev-latest release on GitHub,
// fetched with `gh`. Without them the specs that need a shop or a region sale fail.
val economyRepository = "ParadauxIO/hibernia-economy"
val hiberniaEconomyDir = providers.gradleProperty("hiberniaEconomyDir").orElse("../hibernia-economy")
    .map { rootProject.file(it) }

val installE2eEconomy by tasks.registering {
    description = "Installs the economy plugins into the test server."
    val releaseDir = hiberniaEconomyDir.map { File(it, "release") }
    val downloadDir = e2eDownloads.map { it.dir("economy").asFile }
    val pluginsDir = e2eRunDir.dir("plugins").asFile
    doLast {
        // Treasury refuses to start unless all four economy plugins are there, at one version.
        val dataFolders = mapOf("treasury" to "Treasury", "business" to "Business",
            "chestshop" to "ChestShop", "treasury-api-plugin" to "TreasuryAPI")
        val installed = Regex("(treasury|business|chestshop|treasury-api-plugin)-\\d.*\\.jar")
        fun jarsIn(folder: File): List<File> =
            folder.listFiles { file -> installed.matches(file.name) }?.toList().orEmpty()

        var jars = jarsIn(releaseDir.get())
        if (jars.size < dataFolders.size) {
            // The release's jar names carry its commit, so a new release is a new set of names.
            val (listed, names) = runCommand("gh", "release", "view", "dev-latest", "-R", economyRepository,
                "--json", "assets", "-q", ".assets[].name")
            val wanted = names.lines().filter { installed.matches(it) }
            val folder = downloadDir.get().apply { mkdirs() }
            if (listed == 0 && wanted.size == dataFolders.size) {
                if (!wanted.all { File(folder, it).exists() }) {
                    jarsIn(folder).forEach { it.delete() }
                    val (downloaded, output) = runCommand("gh", "release", "download", "dev-latest", "-R",
                        economyRepository, "-p", "*.jar", "-D", folder.absolutePath, "--clobber")
                    check(downloaded == 0) { "Could not download the economy plugins: $output" }
                }
                jars = wanted.map { File(folder, it) }
            } else {
                // Offline, or gh is missing: whatever an earlier run fetched.
                jars = jarsIn(folder)
            }
        }
        pluginsDir.listFiles { file -> installed.matches(file.name) }?.forEach { it.delete() }
        if (jars.size != dataFolders.size) {
            logger.warn("No economy plugins: no release in ${releaseDir.get()} and none from $economyRepository."
                + " The shop and region-sale specs will fail.")
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

        val (created, output) = runCommand("docker", "exec", "vaultstorage-dev-mariadb", "mariadb", "-uroot",
            "-proot", "-e", "CREATE DATABASE IF NOT EXISTS treasury")
        check(created == 0) { "Could not create the treasury database in the dev MariaDB: $output" }
    }
}

// Realty itself, so that a region really changes hands. Its plugin jar is not in any Maven
// repository: it is taken from -PrealtyJar=<file>, or from the jars the latest successful build
// of Realty's main branch keeps on GitHub, fetched with `gh`. Realty needs an economy, so it
// runs after installE2eEconomy and only enables when that installed one.
val realtyRepository = "MCCitiesNetwork/realty"
val realtyJarOverride = providers.gradleProperty("realtyJar").map { rootProject.file(it) }

val installE2eRealty by tasks.registering {
    description = "Installs Realty into the test server."
    mustRunAfter(installE2eEconomy)
    val override = realtyJarOverride
    val downloadDir = e2eDownloads.map { it.dir("realty").asFile }
    val pluginsDir = e2eRunDir.dir("plugins").asFile
    doLast {
        val pluginJar = Regex("realty-paper-\\d.*-all\\.jar")
        fun jarIn(folder: File): File? = folder.walkTopDown().firstOrNull { pluginJar.matches(it.name) }

        var jar: File? = override.orNull
        if (jar == null) {
            val (listed, run) = runCommand("gh", "run", "list", "-R", realtyRepository, "--branch", "main",
                "--workflow", "Build", "--status", "success", "--limit", "1",
                "--json", "databaseId,headSha", "-q", ".[0] | \"\\(.databaseId) \\(.headSha)\"")
            val parts = run.trim().split(" ")
            if (listed == 0 && parts.size == 2) {
                val folder = File(downloadDir.get(), parts[1])
                if (jarIn(folder) == null) {
                    downloadDir.get().deleteRecursively()
                    folder.mkdirs()
                    val (downloaded, output) = runCommand("gh", "run", "download", parts[0], "-R", realtyRepository,
                        "-n", "jars-${parts[1]}", "-D", folder.absolutePath)
                    check(downloaded == 0) { "Could not download Realty's jars: $output" }
                }
                jar = jarIn(folder)
            } else {
                // Offline, or gh is missing: whatever an earlier run fetched.
                jar = jarIn(downloadDir.get().apply { mkdirs() })
            }
        }
        pluginsDir.listFiles { file -> file.name.startsWith("realty-") && file.name.endsWith(".jar") }
            ?.forEach { it.delete() }
        if (jar == null || !jar.exists()) {
            logger.warn("No Realty jar: none given with -PrealtyJar and none from $realtyRepository."
                + " The region-sale specs will fail.")
            return@doLast
        }
        jar.copyTo(File(pluginsDir, jar.name), overwrite = true)
        // Realty prepends "jdbc:" itself and creates its own tables, in a database of its own.
        File(pluginsDir, "Realty/database.yml").apply { parentFile.mkdirs() }.writeText(
            "url: 'mariadb://127.0.0.1:3309/realty'\nusername: 'root'\npassword: 'root'\n")
        val (created, output) = runCommand("docker", "exec", "vaultstorage-dev-mariadb", "mariadb", "-uroot",
            "-proot", "-e", "CREATE DATABASE IF NOT EXISTS realty")
        check(created == 0) { "Could not create the realty database in the dev MariaDB: $output" }
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
    finalizedBy(usePaperStableBuild, installE2eModules, installE2eEconomy, installE2eRealty)
}
tasks.matching { it.name == "plugwrightTestLocal" || it.name == "plugwrightRunServerLocal" }.configureEach {
    dependsOn(usePaperStableBuild, installE2eModules, installE2eEconomy, installE2eRealty)
}
// Only a test run starts from empty tables; a server run by hand keeps what is there.
// `plugwrightTest` runs the environment itself, not through `plugwrightTestLocal`.
tasks.matching { it.name == "plugwrightTest" || it.name == "plugwrightTestLocal" }.configureEach {
    dependsOn(resetE2eData)
}
listOf(usePaperStableBuild, installE2eModules, installE2eEconomy, installE2eRealty).forEach { task ->
    task.configure { mustRunAfter(tasks.matching { it.name == "plugwrightProvisionLocal" }) }
}
