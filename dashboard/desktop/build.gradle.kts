plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":dashboard"))
            implementation(compose.desktop.currentOs)
        }
    }
}

compose.desktop {
    application {
        mainClass = "net.sdfgsdfg.dashboard.desktop.MainKt"
        nativeDistributions {
            packageName = "Trio Ops Cockpit"
            packageVersion = "0.1.0"
        }
    }
}
