plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // Puts Shadow on every build script's classpath, so the relocation convention can
    // configure shadowJar. Projects therefore apply com.gradleup.shadow without a version.
    implementation(libs.shadow.gradle.plugin)
}
