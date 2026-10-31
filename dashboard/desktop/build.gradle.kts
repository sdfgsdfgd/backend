import java.io.File
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask

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

val desktopAppName = "Trio Ops Cockpit"

compose.desktop {
    application {
        mainClass = "net.sdfgsdfg.dashboard.desktop.MainKt"
        nativeDistributions {
            modules("java.net.http") // Compose jlink workaround: OpsApi.jvm.kt needs HttpClient; the stripped runtime otherwise omits this JDK module.
            packageName = desktopAppName
            packageVersion = "1.0.0"
            macOS {
                bundleID = "net.sdfgsdfg.dashboard.desktop"
            }
        }
    }
}

val installApp by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Installs the packaged Mac app in ~/Applications."
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    from(tasks.named<AbstractJPackageTask>("createDistributable").flatMap {
        it.destinationDir.dir("${it.packageName.get()}.app")
    })
    into("${providers.systemProperty("user.home").get()}/Applications/$desktopAppName.app")
    doFirst {
        val executableDir = destinationDir.resolve("Contents/MacOS").path + "/"
        check(ProcessHandle.allProcesses().use { processes ->
            processes.noneMatch { it.info().command().orElse("").startsWith(executableDir) }
        }) { "Close ${destinationDir.nameWithoutExtension} before installing an update." }
    }
}

tasks.register<Exec>("installAndRun") {
    group = "distribution"
    description = "Builds, installs, pins, and opens the Mac app."
    dependsOn(installApp)
    onlyIf { System.getProperty("os.name") == "Mac OS X" }
    val installedApp = installApp.get().destinationDir
    val commands = providers
    doFirst {
        val bins = listOf("/opt/homebrew/bin", "/usr/local/bin").map(::File)
        val dockutil = bins.map { it.resolve("dockutil") }.firstOrNull(File::canExecute) ?: run {
            val brew = bins.map { it.resolve("brew") }.firstOrNull(File::canExecute)
                ?: error("Homebrew is required to install dockutil.")
            logger.lifecycle("Installing dockutil with Homebrew…")
            commands.exec {
                commandLine(brew, "install", "dockutil")
                environment("HOMEBREW_NO_AUTO_UPDATE", "1")
                environment("HOMEBREW_NO_INSTALL_CLEANUP", "1")
            }.result.get().assertNormalExitValue()
            brew.resolveSibling("dockutil")
        }
        val pinned = commands.exec {
            commandLine(dockutil, "--find", installedApp)
            isIgnoreExitValue = true
        }.result.get().exitValue == 0
        if (!pinned) commands.exec {
            commandLine(dockutil, "--add", installedApp)
        }.result.get().assertNormalExitValue()
    }
    commandLine("/usr/bin/open", installedApp)
}
