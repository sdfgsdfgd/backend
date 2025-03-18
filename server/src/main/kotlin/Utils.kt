package net.sdfgsdfg

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

//                   //.//
//                     =   //
//                    ===    //
//                  =======    //
//                ===========    //
//              ===============    //
//      [   The Ultimate "Shell" Extension Function   ]
// 1. Extensive, dedicated, separate supervisorCoroutines for  stdout & stderr
// 2. Log to console + file + in-memory string builder
// 3. (Optionally) stream lines to separate StateFlows if provided.
// 3. Log / timestamps, to file, console, and output collector
// 4. Bulletproof Error Handling — Transparent & Fail-Safe
// =============
// =============
suspend fun String.shell(
    logFile: File? = File(resolveLogDir(), "shell.log"),
    stdoutState: MutableStateFlow<String>? = null,
    stderrState: MutableStateFlow<String>? = null
): String = withContext(Dispatchers.IO + SupervisorJob()) {
    logFile?.appendText("[${timestamp()}] \$ ${this@shell}\n")

    val process = ProcessBuilder("bash", "-c", this@shell)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectError(ProcessBuilder.Redirect.PIPE)
        .start()

    val output = StringBuilder()

    supervisorScope {
        listOf(
            async {
                process.inputStream.bufferedReader().lineFlow().collect { line ->
                    log(line, logFile, output) // 1. Log to console + file + in-memory string builder
                    stdoutState?.let { flow -> // 2. Also update the optional StateFlow for stdout
                        flow.value = "${flow.value}\n $line"
                    }
                }
            },
            async {
                process.errorStream.bufferedReader().lineFlow().collect { line ->
                    log("[ERROR] $line", logFile, output)
                    stderrState?.let { flow ->
                        flow.value = "${flow.value}\n [ERROR] $line"
                    }
                }
            }
        ).awaitAll()
    }

    val exitCode = process.waitFor()
    val exitMessage = "[${timestamp()}] ${if (exitCode == 0) "✓" else "✗"} Exit: $exitCode"
    log(exitMessage, logFile, output) // Log exit status

    output.toString().trim() // Return full combined stdout + stderr
}

// =============
// =============
// =============
// =============

/**
 * Resolve our log directory, from wherever we launch the Ktor
 *
 * Trans-environment Compatibility:
 *  distInstall dir  or  custom folder within repo, via shadowJar or whatever
 *
 */
fun resolveLogDir() = File(System.getProperty("user.dir"), "0_scripts/logs").takeIf { it.mkdirs() || it.exists() }
    ?: error("Failed to create/access log directory: ${System.getProperty("user.dir")}/0_scripts/logs")

/**
 * Streams lines from a BufferedReader as a Flow.
 */
private fun BufferedReader.lineFlow(): Flow<String> = flow {
    for (line in this@lineFlow.lineSequence()) emit(line)
}.flowOn(Dispatchers.IO)

/**
 * Logs a message to console, file, and output collector.
 */
fun log(message: String, logFile: File?, output: StringBuilder? = null) {
    println(message)
    logFile?.appendText("[${timestamp()}] $message\n")
    output?.append(message)?.append("\n")
}

/**
 * Returns the current timestamp in HH:mm:ss format.
 */
private fun timestamp(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
