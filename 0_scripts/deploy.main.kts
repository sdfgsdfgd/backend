#!/usr/bin/env kotlin

import java.io.File
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

val logsDir = scriptDir.resolve("logs").apply { mkdirs() }  // Ensure logs folder exists
val logFile = logsDir.resolve("app.log").apply { if (!exists()) createNewFile() }
val logBuild = logsDir.resolve("build.log").apply { if (!exists()) createNewFile() }
// endregion

// =====================
// =====================
// region UTILITIES
// =====================
// =====================
fun findProcessId(port: Int): String? = runCatching {
    ServerSocket(port).use { null }
}.getOrElse {
    logException(it, logFile, "Failed to check if port $port is in use")
    "lsof -ti tcp:$port".shell().trim().takeIf { it.isNotEmpty() }
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
// endregion

//         ==============     //
//      ====================    //
//   ==========================  //
//  ============================  //
// ============= CMD ============ //
//  ============================  //
//   ==========================  //
val cmd = args.firstOrNull() ?: "deploy"
when (cmd) {
    "start" -> findProcessId(port)?.let { println("⚠️ $appName already running (PID: $it). Use './deploy.main.kts restart'.") }
        ?: runCatching {
            println("🚀 Starting $appName...")


            // =========================================  TODO:   Improve,  Daemonize    on Server       ======================================
            "nohup java -jar $jarPath &".shell(logFile)


            println("✅ Started.")
        }.onFailure { logException(it, logFile, "Failed to start application") }

    "stop" -> findProcessId(port)?.let { pid ->
        runCatching {
            println("🛑 Stopping $appName (PID: $pid)...")
            "kill -9 $pid".shell()
        }.onFailure {
            logException(it, logFile, "Failed to stop application OR there was no process to stoppu")
        }.onSuccess { println("✅ Stopped process.") }
    } ?: println("⚠️ $appName is not running.")

    "restart" -> runCatching {
        println("🔄 Restarting $appName...")
        "${scriptPath} stop".shell()
        Thread.sleep(2_000)
        "${scriptPath} start".shell()
    }.onFailure {
        logException(it, logFile, "Failed to restart application")
    }.onSuccess { println("✅ Restarted.") }

    "status" -> findProcessId(port)
        ?.let { println("✅ $appName is running (PID: $it)") }
        ?: println("⚠️ $appName is NOT running.")

    "deploy", "" -> runCatching {
        println("🚀 Deploying new version of $appName...")

        printCondition(
            "? $appName running ? (PID: ${findProcessId(port)}) ?",
            findProcessId(port) != null
        )

        // Single build command (logged to buildLog)
        val buildOutput = "./gradlew shadowJar".shell(logBuild)
            .also { println("\n 🔨 Building new version... [ $it ]") }

        if (buildOutput.isEmpty()) error("Build failed! Check ${logBuild.absolutePath}.")

        println("✅ Build successful!")

        // Stop old instance if running
        findProcessId(port)?.let { pid ->
            println("🛑 Stopping old instance (PID: $pid)...")
            "kill $pid".shell()
            Thread.sleep(2_000)
            println("✅ Stopped.")
        }

        // Start fresh
        println("🚀 Starting new instance...")
        "nohup java -jar $jarPath &".shell(logFile)
    }.onFailure {
        logException(it, logBuild, "Deployment failed")
    }.onSuccess { println("✅ Deployment complete.") }

    else -> {
        println("❌ Unknown command: $cmd")
        println("Usage: ./deploy.main.kts [start|stop|restart|status|deploy]")
        exitProcess(1)
    }
}