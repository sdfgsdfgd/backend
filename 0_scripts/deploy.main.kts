#!/usr/bin/env kotlin

import java.io.File
import java.net.BindException
import java.net.ServerSocket
import java.time.LocalDateTime
import kotlin.io.path.Path
import kotlin.system.exitProcess

// On the why of  `*.main.kts` :
//  The .main.kts extension triggers IntelliJ's built-in script definition for top-level execution scripts, improving IDE support.
//  A plain *.kts file is recognized as a generic Kotlin script, no syntax highlighting, (also no execution or?)

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

// xx === Shell === xx
// Redirect (vs inheritIO) allows to pipe ALSO to file
fun String.shell(outputFile: File? = null): String = runCatching {
    val process = ProcessBuilder("bash", "-c", this).apply {
        redirectErrorStream(true)
    }.start()





    val reader = process.inputStream.bufferedReader()
    val outputBuffer = StringBuilder()

    reader.useLines { lines ->
        lines.forEach { line ->
            println(line)  // Print to terminal
            outputBuffer.appendLine(line)
            outputFile?.appendText("🔸 $line\n")  // Append to log file
        }
    }

    val exitCode = process.waitFor()






//    val output = process.inputStream.bufferedReader().readText().trim()
//    val exitCode = process.waitFor()

    // Print raw process exit code
    println("🔎 Shell command: $this | Exit code: $exitCode")

    // Append output to file if needed
//    outputFile?.appendText("$output\n")

    // Log and return output
    return outputBuffer.toString() // .also { println("🔸 Output: ${it.ifEmpty { "!!!output empty!!" }}") }
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

// Start server
fun startServer() {
    println("🚀 Starting $appName...")

    binPath.shell(logFile)

    val isThisEnoughTimeSir = 4000L
    Thread.sleep(isThisEnoughTimeSir)

    // If findProcessId still returns null, we consider that a failure
    findProcessId(port)?.let {
        println("✅ $appName started successfully (PID: ${it})")
    } ?: run {
        throw RuntimeException("Server failed to start after ${isThisEnoughTimeSir / 1000L} seconds on port $port (no process found). Check logs.")
    }
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
val process = findProcessId(port)

when (cmd) {
    "start" -> process?.let {
        println("⚠️ $appName already running (PID: $it). Use './deploy.main.kts restart'.")
    } ?: runCatching {
        startServer()
    }.onFailure {
        logException(it, logFile, "Failed to start application")
        exitProcess(1)  // Return non-zero exit code on failure
    }.onSuccess {
        println("✅ Start command complete.")
    }

    "stop" -> process?.let { pid ->
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

    "restart" -> runCatching {
        println("🔄 Restarting $appName...")
        "$scriptPath stop".shell()
        Thread.sleep(2000)
        "$scriptPath start".shell()
    }.onFailure {
        logException(it, logFile, "Failed to restart application")
        exitProcess(1)
    }.onSuccess {
        println("✅ Restarted.")
    }

    "status" -> process
        ?.let { println("✅ $appName is running (PID: $it)") }
        ?: println("⚠️ $appName is NOT running.")

    "deploy", "" -> runCatching {
        println("🚀 Deploying new version of $appName...")

        println("\n\n\n\n\n 🔨 Pulling latest changes from Git...")
        "sudo -u x git pull".shell(logBuild)
        println("✅ Git pull complete!")
        println("🔨 Rebuilding project (clean + build + installDist)...")
        "./gradlew clean build installDist".shell(logBuild)
        println("✅ Rebuild & installDist successful!")

        process?.let { pid ->
            println("   $appName currently running (PID=$pid)")
            println("🛑 Stopping old instance (PID: $pid)...")
            "kill -9 $pid".shell()
            Thread.sleep(2000)
            println("✅ Stopped old instance.")
        }

        startServer()

    }.onFailure {
        logException(it, logBuild, "Deployment failed")
        println("❌ Deployment failed: ${it.message}")
        exitProcess(1)
    }.onSuccess { println("✅ Deployment complete.") }

    else -> {
        println("❌ Unknown command: $cmd")
        println("Usage: ./deploy.main.kts [start|stop|restart|status|deploy]")
        exitProcess(1)
    }
}