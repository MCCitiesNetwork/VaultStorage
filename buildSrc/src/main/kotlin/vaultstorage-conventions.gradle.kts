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
    maven("https://repo.essentialsx.net/releases/") { name = "essentialsx" }
    maven("https://repo.essentialsx.net/snapshots/") { name = "essentialsx-snapshots" }
    maven("https://repo.opencollab.dev/main/") { name = "opencollab" }
    maven("https://repo.minebench.de/") { name = "minebench" }
    maven("https://maven.democracycraft.net/releases") { name = "democracycraft" }
    maven("https://maven.minecraftcitiesnetwork.com/snapshots") { name = "mccities-snapshots" }
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
    }

    withType(JavaCompile::class) {
        options.release.set(targetJavaVersion)
        options.encoding = "UTF-8"
        options.isFork = true
        options.isDeprecation = true
    }
}
