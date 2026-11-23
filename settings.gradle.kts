rootProject.name = "vaultstorage"

include("plugin")
include("modules:namebook-adapter")
include("modules:floodgate-adapter")
include("modules:chestshop-adapter")
include("modules:realty")
include("modules:griefprevention-adapter")
include("modules:player-notifications-adapter")
// Test-only plugins for the live-test server. Never shipped.
include("e2e-plugins:realty-stub")
