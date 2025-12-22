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
fun String.shell(outputFile: File? = null, logCommand: Boolean = true): String = runCatching {
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
    if (logCommand) {
        println("🔎 Shell command: $this | Exit code: $exitCode")
    }

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
            "kill -15 $pid".shell()

            val timeoutMs = 20_000L
            val pollMs = 1_000L
            var waitedMs = 0L
            while (waitedMs < timeoutMs) {
                Thread.sleep(pollMs)
                val stillRunning = "ps -p $pid -o pid=".shell().trim().isNotEmpty()
                if (!stillRunning) {
                    println("✅ Stopped process.")
                    return@runCatching
                }
                waitedMs += pollMs
            }

            println("⚠️ Graceful stop timed out; sending SIGKILL...")
            "kill -9 $pid".shell()
            Thread.sleep(1_000L)
            println("✅ Stopped process.")
        }.onFailure {
            logException(it, logFile, "Failed to stop application")
            exitProcess(1)
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

        println("\n\n\n\n\n 🔨 Syncing latest changes from Git...")
        val gitSyncCmd = """
            set -euo pipefail
            orig_head=${'$'}(git rev-parse HEAD)
            dirty=0
            if [ -n "${'$'}(git status --porcelain)" ]; then dirty=1; fi
            if [ "${'$'}dirty" -eq 1 ]; then git stash push -u -m "autostash:backend ${'$'}(date +%s)"; fi
            git fetch origin main --prune --quiet
            ahead_counts=${'$'}(git rev-list --left-right --count HEAD...refs/remotes/origin/main)
            set -- ${'$'}ahead_counts
            local_ahead=${'$'}1
            remote_ahead=${'$'}2
            if [ "${'$'}remote_ahead" -eq 0 ]; then
              echo "✅ Git sync: already up to date; no rebase needed."
            else
              echo "🔁 Git sync: rebasing to pull ${'$'}remote_ahead remote commits (local ahead=${'$'}local_ahead)."
              if ! git rebase refs/remotes/origin/main; then
                echo "⚠️ Git sync: rebase failed; restoring original state."
                git rebase --abort || true
                git reset --hard "${'$'}orig_head"
                if [ "${'$'}dirty" -eq 1 ]; then
                  if ! git stash pop --index; then
                    echo "Stash pop conflicted; leaving stash."
                    exit 1
                  fi
                fi
                exit 1
              fi
              echo "✅ Git sync: rebase complete."
            fi
            if [ "${'$'}dirty" -eq 1 ]; then
              if ! git stash pop --index; then
                echo "Stash pop conflicted; restoring original state."
                git reset --hard "${'$'}orig_head"
                if ! git stash pop --index; then
                  echo "Failed to restore original state; leaving stash."
                  exit 1
                fi
                exit 1
              fi
            fi
        """.trimIndent()
        gitSyncCmd.shell(logBuild, logCommand = false)
        println("✅ Git sync complete!")
        println("🔨 Rebuilding project (clean + build + installDist)...")
        "./gradlew clean build installDist".shell(logBuild)
        println("✅ Rebuild & installDist successful!")

        process?.let { pid ->
            println("   $appName currently running (PID=$pid)")
            "$scriptPath stop".shell(logBuild)
            Thread.sleep(2000)
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
