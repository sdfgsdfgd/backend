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
            modules("java.net.http") // Compose jlink workaround: OpsApi.jvm.kt needs HttpClient; the stripped runtime otherwise omits this JDK module.
            packageName = "Trio Ops Cockpit"
            packageVersion = "1.0.0"
        }
    }
}
