package net.sdfgsdfg

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// xx What is the ultimate, ideal Data Structure for collecting and propagating stdout/stderr pipelines — beaming containerized streams across thousands of websockets/gRPC —
//  in the most performant, memory-safe, and idiomatic way?
//
//                   //.//
//                     =   //
//                    ===    //
//                  =======    //
//                ===========    //
//              ===============    //
//      [   The Ultimate "Shell" Extension Function   ]
// 1. stdout & stderr :  SharedFlows with buffers, Hot Flows are best for logging even outside collection. Extensive, dedicated, separate supervisorCoroutines.
// 2. stdin --> Channel :  Best "Cold" structure for piping stdin on demand, independently of other streams
// 3. Log to console + file + in-memory string builder
// 4. (Optionally) stream lines to separate StateFlows if provided.
// 5. Log / timestamps, to file, console, and output collector
// 6. Bulletproof Error Handling — Transparent & Fail-Safe
// =============
// =============
suspend fun String.shell(
    logFile: File? = File(resolveLogDir(), "shell.log"),
    stdoutState: MutableSharedFlow<String>? = null,  // xx ideally MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1024, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    stderrState: MutableSharedFlow<String>? = null,
    stdinChannel: Channel<String>? = null,
    printNlog: Boolean = true,
    onLine: (suspend (line: String, isError: Boolean) -> Unit)? = null,
) = withContext(Dispatchers.IO + SupervisorJob()) {
    println("🚀 Running   -->     ${this@shell}")
    val process = ProcessBuilder("bash", "-c", this@shell)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectError(ProcessBuilder.Redirect.PIPE)
        .redirectInput(ProcessBuilder.Redirect.PIPE)
        .start()

    val stdinWriter = process.outputStream.bufferedWriter()

    supervisorScope { // supervisors don't crash other threads if crashed
        listOf(
            async {
                process.inputStream.bufferedReader().lineFlow().collect { line ->
                    onLine?.invoke(line, false)
                    if(printNlog) log(line, logFile) // 1. Print to console  2. File
                    stdoutState?.emit(line)
                }
            },
            async {
                process.errorStream.bufferedReader().lineFlow().collect { line ->
                    onLine?.invoke(line, true)
                    if(printNlog) log("[ERROR] $line", logFile)
                    stderrState?.emit("[ERROR] $line")
                }
            },
            async {
                stdinChannel?.consumeEach { input ->
                    stdinWriter.write(input)
                    stdinWriter.flush()
                }
            }
        ).awaitAll()
    }

    val exitCode = process.waitFor()
    val exitMessage = "[${timestamp()}] ${if (exitCode == 0) "✓" else "✗"} Exit: $exitCode"
    log(exitMessage, logFile)

    return@withContext exitCode
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
fun BufferedReader.lineFlow(): Flow<String> = flow {
    for (line in this@lineFlow.lineSequence()) emit(line)
}.flowOn(Dispatchers.IO)

/**
 * Logs a message to console, file, and output collector.
 */
private const val DEFAULT_LOG_FILE = "server.log"

private fun File.asLogFile(): File = if (isDirectory) File(this, DEFAULT_LOG_FILE) else this

fun log(message: String, logFile: File? = null) {
    println(message)
    logFile?.asLogFile()?.appendText("[${timestamp()}] $message\n")
}

/**
 * Returns the current timestamp in HH:mm:ss format.
 */
private fun timestamp(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
