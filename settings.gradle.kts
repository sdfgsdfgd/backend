plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "backend"

include(":server")
include(":core")
include(":dashboard")
include(":dashboard:web")
include(":dashboard:desktop")
