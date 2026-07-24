import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.kotlin.plugin.serialization) apply false
    alias(libs.plugins.kotlin.plugin.compose) apply false
    alias(libs.plugins.compose) apply false
//    alias(libs.plugins.kotlinx.rpc.plugin) apply false
}

// Stable server lifecycle aliases for deploy scripts and future module orchestration.
// Keep these server-scoped so backend deploy does not inherit app/web/desktop checks.
tasks.register("verifyServer") {
    group = "verification"
    description = "Runs the production server verification set."
    dependsOn(":core:jvmTest", ":server:check")
}

tasks.register("installServer") {
    group = "distribution"
    description = "Installs the runnable server distribution."
    dependsOn(":server:installDist")
}

tasks.register("verifyDashboard") {
    group = "verification"
    description = "Checks the dashboard and builds its web and desktop entrypoints."
    dependsOn(
        ":dashboard:check",
        ":dashboard:web:wasmJsBrowserDistribution",
        ":dashboard:desktop:jvmJar",
    )
}

subprojects {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/kotlin-js-wrappers") }
        maven { url = uri("https://jitpack.io") }
    }

    group = "net.sdfgsdfg"
    version = "0.0.1"

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension>("kotlin") {
            jvmToolchain(21)
        }
    }

    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<KotlinMultiplatformExtension>("kotlin") {
            jvmToolchain(21)
        }
    }
}
