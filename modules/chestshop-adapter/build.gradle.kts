plugins {
    id("vaultstorage-module-conventions")
}

dependencies {
    compileOnly(libs.chestshop)
    // The shop registry. Only TreasuryShopDirectory names its types.
    compileOnly(libs.treasury.api) { isTransitive = false }
    testImplementation(libs.treasury.api) { isTransitive = false }
}
