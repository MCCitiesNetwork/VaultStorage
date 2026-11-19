plugins {
    java
    checkstyle
}

// Precompiled script plugins cannot use the generated `libs` accessors.
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

group = "com.minecraftcitiesnetwork"
version = providers.gradleProperty("version").get()

val targetJavaVersion = libs.findVersion("java").get().requiredVersion.toInt()

java.toolchain.languageVersion.set(JavaLanguageVersion.of(targetJavaVersion))

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
    maven("https://repo.codemc.io/repository/maven-public/") { name = "codemc" }
    maven("https://maven.enginehub.org/repo/") { name = "enginehub" }
    maven("https://repo.opencollab.dev/main/") { name = "opencollab" }
    maven("https://repo.minebench.de/") { name = "minebench" }
    maven("https://maven.democracycraft.net/releases") { name = "democracycraft" }
    maven("https://maven.minecraftcitiesnetwork.com/releases") { name = "mccities-releases" }
    maven("https://maven.minecraftcitiesnetwork.com/snapshots") { name = "mccities-snapshots" }
    // Treasury's API, for the shop registry. Filtered, so nothing else resolves from it.
    maven("https://repo.paradaux.io/releases") {
        name = "paradaux-releases"
        content { includeGroup("io.paradaux") }
    }
    // GriefPrevention is published only here. Filtered, so nothing else resolves from it.
    maven("https://jitpack.io") {
        name = "jitpack"
        content { includeGroup("com.github.TechFortress") }
    }
}

dependencies {
    compileOnly(libs.findLibrary("annotations").get())
    testCompileOnly(libs.findLibrary("annotations").get())
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

checkstyle {
    toolVersion = libs.findVersion("checkstyle").get().requiredVersion
    configDirectory.set(rootProject.layout.projectDirectory.dir("config/checkstyle"))
}

tasks {
    test {
        useJUnitPlatform()
        // A Gradle test JVM inherits no shell environment, so Testcontainers needs the socket named.
        val dockerSocket = listOf(
            File(System.getProperty("user.home"), ".docker/run/docker.sock"),
            File(System.getProperty("user.home"), ".colima/default/docker.sock"),
            File("/var/run/docker.sock"),
        ).firstOrNull { it.exists() }
        if (System.getenv("DOCKER_HOST") == null && dockerSocket != null) {
            environment("DOCKER_HOST", "unix://${dockerSocket.absolutePath}")
            environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", dockerSocket.absolutePath)
        }
        // Docker Engine 29 rejects API versions below 1.44.
        if (System.getenv("DOCKER_API_VERSION") == null) {
            environment("DOCKER_API_VERSION", "1.44")
            systemProperty("api.version", "1.44")
        }
    }

    withType(JavaCompile::class) {
        options.release.set(targetJavaVersion)
        options.encoding = "UTF-8"
        options.isFork = true
        options.isDeprecation = true
    }
}
