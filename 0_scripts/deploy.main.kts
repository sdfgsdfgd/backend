#!/usr/bin/env kotlin

import java.io.File
import java.net.BindException
import java.net.ServerSocket
import java.time.LocalDateTime
import kotlin.io.path.Path
import kotlin.system.exitProcess

// On the why of  `*.main.kts` :
//  The .main.kts extension triggers IntelliJ's built-in script definition for top-level execution scripts, improving IDE support.
//  A plain *.kts file is recognized as a generic Kotlin script

// =====================
// =====================
// region CONFIGURATION
// =====================
val appName = "backend"
val port = 80

val scriptDir = Path("./0_scripts/").toAbsolutePath().normalize().toFile().canonicalFile
val scriptPath = scriptDir.resolve("deploy.main.kts").canonicalPath
val jarPath = scriptDir.resolve("../server/build/libs/server-all.jar").canonicalPath
val binPath = scriptDir.resolve("../server/build/install/server/bin/server").canonicalPath
// /Users/x/Desktop/kotlin/backend/server/build/install/server/bin/server

val logsDir = scriptDir.resolve("logs").apply { mkdirs() }  // Ensure logs folder exists
val logFile = logsDir.resolve("app.log").apply { if (!exists()) createNewFile() }
val logBuild = logsDir.resolve("build.log").apply { if (!exists()) createNewFile() }
// endregion

// =====================
// =====================
// region UTILITIES
// =====================
// =====================
fun findProcessId(port: Int): String? = try {
    // If we can bind, the port is free => return null
    ServerSocket(port).use { null }
} catch (e: BindException) {
    // Normal case: port is in use => let's get the PID via lsof
    "lsof -ti tcp:$port".shell().trim().takeIf { it.isNotEmpty() }
} catch (e: Throwable) {
    // Unexpected error => log it
    logException(e, logFile, "Error checking if port $port is in use")
    null
}


//  todo:       .inheritIO().also { println("Running: $this") }  instead ?
fun String.shell(outputFile: File? = null): String = runCatching {
    val process = ProcessBuilder("bash", "-c", this).apply {
        redirectErrorStream(true)
    }.start()

    val output = process.inputStream.bufferedReader().readText().trim()
    val exitCode = process.waitFor()

    // Print raw process exit code
    println("🔎 Shell command: $this | Exit code: $exitCode")

    // Append output to file if needed
    outputFile?.appendText("$output\n")

    // Log and return output
    return output.also { println("🔸 Output: ${it.ifEmpty { "!!!output empty!!" }}") }
}.getOrElse {
    logException(it, outputFile ?: logFile, "Shell command failed: $this")
    return ""
}

fun printCondition(message: String, condition: Boolean) = println(if (condition) "✅ $message" else "❌ $message")

fun logException(e: Throwable, logFile: File, message: String) {
    val timestamp = LocalDateTime.now()
    val logMessage = "$timestamp | ERROR | $message: ${e.localizedMessage}\n"
    logFile.appendText(logMessage)
    println("❌ Logged Error: $message (Check ${logFile.absolutePath})")
}

/**
 * Attempts to start the server in the background (nohup + &),
 * waits a few seconds to see if it fails immediately.
 * Throws an exception if the process fails to start or port is not in use.
 */
fun startServer() {
    println("🚀 Starting $appName...")

    binPath.shell(logFile)

    // Wait for a bit to see if it fails quickly
    Thread.sleep(4000)

    // If findProcessId still returns null, we consider that a failure
    if (findProcessId(port) == null) {
        throw RuntimeException("Server failed to start on port $port (no process found). Check logs.")
    }
    println("✅ $appName started successfully (PID: ${findProcessId(port)})")
}

// endregion

//              xx ShadowJar version
//               "nohup java -jar $jarPath &".shell(logFile)
//         ==============     //
//      ====================    //
//   ==========================  //
//  ============================  //
// ============= CMD ============ //
//  ============================  //
//   ==========================  //
val cmd = args.firstOrNull() ?: "deploy"

when (cmd) {
    "start" -> {
        // If already running, warn
        findProcessId(port)?.let {
            println("⚠️ $appName already running (PID: $it). Use './deploy.main.kts restart'.")
        } ?: runCatching {
            startServer()
        }.onFailure {
            logException(it, logFile, "Failed to start application")
            exitProcess(1)  // Return non-zero exit code on failure
        }.onSuccess {
            println("✅ Start command complete.")
        }
    }

    "stop" -> {
        // If running, kill it
        findProcessId(port)?.let { pid ->
            runCatching {
                println("🛑 Stopping $appName (PID: $pid)...")
                "kill -9 $pid".shell()
            }.onFailure {
                logException(it, logFile, "Failed to stop application")
                exitProcess(1)
            }.onSuccess {
                println("✅ Stopped process.")
            }
        } ?: println("⚠️ $appName is not running.")
    }

    "restart" -> {
        runCatching {
            println("🔄 Restarting $appName...")
            "$scriptPath stop".shell()  // calls this same script's 'stop' command
            Thread.sleep(2000)
            "$scriptPath start".shell() // calls this same script's 'start' command
        }.onFailure {
            logException(it, logFile, "Failed to restart application")
            exitProcess(1)
        }.onSuccess {
            println("✅ Restarted.")
        }
    }

    "status" -> {
        findProcessId(port)
            ?.let { println("✅ $appName is running (PID: $it)") }
            ?: println("⚠️ $appName is NOT running.")
    }

    "deploy", "" -> {
        runCatching {
            println("🚀 Deploying new version of $appName...")

            // Show if something is already running (unchanged)
            val oldPid = findProcessId(port)
            if (oldPid != null) {
                println("   $appName currently running (PID=$oldPid)")
            }

            // --- NEW LINES BEGIN ---
            println("🔨 Pulling latest changes from Git...")
            "git pull".shell(logBuild)
            println("✅ Git pull complete!")

            println("🔨 Rebuilding project (clean + build + installDist)...")
            "./gradlew clean build installDist".shell(logBuild)
            println("✅ Rebuild & installDist successful!")
            // --- NEW LINES END ---

            // Stop old instance if running (unchanged)
            oldPid?.let { pid ->
                println("🛑 Stopping old instance (PID: $pid)...")
                "kill $pid".shell()
                Thread.sleep(2000)
                println("✅ Stopped old instance.")
            }

            // Start new instance (unchanged)
            startServer()

        }.onFailure {
            logException(it, logBuild, "Deployment failed")
            println("❌ Deployment failed: ${it.message}")
            exitProcess(1)
        }.onSuccess {
            println("✅ Deployment complete.")
        }
    }


    else -> {
        println("❌ Unknown command: $cmd")
        println("Usage: ./deploy.main.kts [start|stop|restart|status|deploy]")
        exitProcess(1)
    }
}