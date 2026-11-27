plugins {
    `java-library`
    id("vaultstorage-conventions")
    id("com.gradleup.shadow")
    id("vaultstorage-shared-relocation")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    // A module compiles against the host and never bundles it or its libraries.
    compileOnly(project(":plugin"))
    compileOnly(libs.findLibrary("paper-api").get())
    compileOnly(libs.findLibrary("plugin-infrastructure").get())
    testImplementation(project(":plugin"))
    testRuntimeOnly(libs.findLibrary("paper-api").get())
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier = ""
}

tasks.named<Jar>("jar") {
    archiveClassifier = "unshaded"
}

tasks.named("build") {
    dependsOn("shadowJar")
}

// The host consumes this to install module jars into the run directory.
val moduleJar: Configuration by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(moduleJar.name, tasks.named<Jar>("shadowJar"))
}
