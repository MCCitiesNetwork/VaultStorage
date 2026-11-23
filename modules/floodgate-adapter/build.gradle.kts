plugins {
    id("vaultstorage-module-conventions")
}

dependencies {
    compileOnly(libs.floodgate.api)
    // FormPlanTest builds menus, whose text is an Adventure type.
    testImplementation(libs.paper.api)
}
