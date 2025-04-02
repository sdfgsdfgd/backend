package net.sdfgsdfg

import io.ktor.server.application.log
import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.server.websocket.application
import io.ktor.server.websocket.sendSerialized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

// ----------------------------------------------------------
// ACTIVE CONTAINERS
// ----------------------------------------------------------
val activeContainers = ConcurrentHashMap<String, ContainerSession>()

data class ContainerSession(
    val containerId: String,
    val scriptPath: String,
    val job: Job,
    val inputFlow: MutableSharedFlow<String>
)

data class ContainerMessage(
    val type: String,
    val messageId: String? = UUID.randomUUID().toString(),
    val script: String? = null,
    val input: String? = null,
    val clientTimestamp: Long? = null,
    val openaiApiKey: String? = null,
)

data class ContainerResponse(
    val type: String = "container_response",
    val messageId: String,
    val status: String, // e.g. "starting", "running", "input_needed", "error", "exited"
    val output: String? = null,
    val serverTimestamp: Long = System.currentTimeMillis()
)

// ----------------------------------------------------------
// 3-SECOND-DEBOUNCED OUTPUT BUFFER
// ----------------------------------------------------------
/**
 *  Collects Docker/container lines, debounces them, and sends them to the client via WebSocket
 *
 * - "input_needed" lines are flushed immediately (no debounce)
 * - Other lines ("running", "error", etc.) are grouped and sent after 3 seconds of inactivity
 * - On [close], any remaining lines are flushed one final time
 *
 *   in my own words:    buffered + debounced <- STDIO/STDOUT/STDERR -> WebSocketServerSession
 */
class DebounceBuffer(
    private val session: WebSocketServerSession,
    private val messageId: String,
    private val bufferTimeoutMs: Long = 3000
) {
    private data class LineItem(val seq: Long, val text: String, val status: String)

    private val nextSeq = java.util.concurrent.atomic.AtomicLong(0) // line unique sequence numbers for logging
    private val lineFlow = MutableSharedFlow<LineItem>(
        replay = 0,
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    // independent .. supervisor scope for da boys:  collector + flusher
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val buffer = mutableListOf<LineItem>()

    @Volatile // Track last arrival time for idle detection
    private var lastItemTime = System.currentTimeMillis()

    init {
        // 1)  [ lineFlow ]
        scope.launch {
            // println("[DebounceBuffer] Collector coroutine started.")
            lineFlow.collect { item ->
                // println("[DebounceBuffer][COLLECT] seq=${item.seq}, status=${item.status},  len=${item.text.length}, text='${item.text}'")

                when (item.status) {
                    "close" -> { // Final flush and exit
                        if (buffer.isNotEmpty()) {
                            // println("[DebounceBuffer][COLLECT] 'close' -> flushing buffer of size=${buffer.size}")
                            flushBuffer(buffer)
                        }
                        // println("[DebounceBuffer][COLLECT] Exiting collector on 'close' sentinel.")
                        return@collect
                    }

                    "input_needed" -> {
                        if (buffer.isNotEmpty()) {
                            // println("[DebounceBuffer][COLLECT] 'input_needed' -> flush buffer size=${buffer.size}")
                            flushBuffer(buffer)
                        }
                        flushBuffer(mutableListOf(item))
                    }

                    else -> buffer += item
                }
                lastItemTime = System.currentTimeMillis()
            }
        }

        // 2) Idle-based flusher. Every 3s, check if no new lines arrived
        scope.launch {
            // println("[DebounceBuffer] Flusher coroutine started.")
            while (isActive) {
                delay(bufferTimeoutMs)
                val now = System.currentTimeMillis()
                val delta = now - lastItemTime
                if (buffer.isNotEmpty() && delta >= bufferTimeoutMs) {
                    println("[DebounceBuffer][FLUSH_TIMER] Idle $delta ms -> flush buffer size=${buffer.size}")
                    flushBuffer(buffer)
                }
            }
        }
    }

    suspend fun addLine(line: String, status: String) {
        val seqNumber = nextSeq.incrementAndGet()
        // println("[DebounceBuffer][ADD] seq=$seqNumber, status=$status, len=${line.length}, text='$line'")
        lineFlow.emit(LineItem(seqNumber, line, status))
    }

    /**
     * Close the buffer: emit a sentinel and wait for the collector to exit.
     */
    suspend fun close() {
        println("[DebounceBuffer][CLOSE] Emitting 'close' sentinel.")
        val seqNumber = nextSeq.incrementAndGet()
        lineFlow.emit(LineItem(seqNumber, "", "close"))
        // Then cancel the scope, or let the collector return naturally.
        // But if you want to be sure everything is flushed, wait for collect to exit:
        scope.coroutineContext.job.cancelAndJoin()
        println("[DebounceBuffer][CLOSE] All coroutines joined.")
    }

    private suspend fun flushBuffer(lines: MutableList<LineItem>) {
        if (lines.isEmpty()) return
        val copy = lines.toList()
        lines.clear()

        // Status for dat batch
        val primaryStatus = if (copy.any { it.status == "error" }) "error" else copy.first().status
        val combinedText = copy.joinToString("\n") { it.text }

        val seqRange = "${copy.first().seq}->${copy.last().seq}"
        println("[DebounceBuffer][FLUSH] Flushing ${copy.size} items, seqRange=$seqRange, status=$primaryStatus, totalChars=${combinedText.length}")

        runCatching {
            session.sendContainerResponse(messageId, primaryStatus, combinedText)
            println("[DebounceBuffer][FLUSH] -> SUCCESS. Sent $seqRange")
        }.onFailure { e ->
            println("[DebounceBuffer][FLUSH] -> ERROR while sending: ${e.message}")
        }
    }
}


// ----------------------------------------------------------
// EXTENSION: Send container response
// ----------------------------------------------------------
suspend fun WebSocketServerSession.sendContainerResponse(
    messageId: String,
    status: String,
    output: String? = null
) {
    sendSerialized(
        ContainerResponse(messageId = messageId, status = status, output = output)
    )
}

// ----------------------------------------------------------
// HANDLE MESSAGES
// ----------------------------------------------------------
suspend fun WebSocketServerSession.handleContainerRequest(
    message: ContainerMessage,
    clientId: String
) {
    val msgId = message.messageId ?: UUID.randomUUID().toString()
    when (message.type.lowercase()) {
        "arcana_start", "container_start" -> startContainer(clientId, msgId, "_0.py", message)
        "container_input" -> sendContainerInput(clientId, msgId, message.input.orEmpty())
        "container_stop" ->
            if (stopContainerInternal(clientId))
                sendContainerResponse(msgId, "exited", "Container stopped")
            else
                sendContainerResponse(msgId, "error", "No active container found")

        else -> sendContainerResponse(msgId, "error", "Unknown container command: ${message.type}")
    }
}

// ----------------------------------------------------------
// START / STOP CONTAINERS
// ----------------------------------------------------------
private suspend fun WebSocketServerSession.startContainer(
    clientId: String,
    messageId: String,
    scriptName: String,
    message: ContainerMessage,
) {
    val workspace = WorkspaceTracker.getCurrentWorkspace(clientId)
        ?: run {
            sendContainerResponse(messageId, "error", "No repository selected.")
            return
        }

    // If there's already a container for this client, stop it first
    stopContainerInternal(clientId)
    sendContainerResponse(messageId, "starting", "Starting analysis for ${workspace.owner}/${workspace.name}...")

    val containerId = UUID.randomUUID().toString().take(8)
    val inputFlow = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val outputBuffer = DebounceBuffer(this, messageId, bufferTimeoutMs = 3000)

    val containerJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
        runCatching {
            activeContainers[clientId] = ContainerSession(containerId, scriptName, coroutineContext.job, inputFlow)

            // INPUT: handle user input lines from the WebSocket, piping them into the container
            val inputJob = launch {
                inputFlow.collect { userInput ->
                    runCatching {
                        withTimeout(1500.seconds) {
                            val exitVal = ContainerCommandBuilder
                                .buildInputCommand(containerId, userInput)
                                .shell { line, isErr ->
                                    // For minimal code, unify error -> "running"
                                    // or keep them distinct as you like
                                    if (isErr && line.isNotBlank()) outputBuffer.addLine(line, "error")
                                    else if (line.isNotBlank()) outputBuffer.addLine(line, "running")
                                }

                            if (exitVal == 0) outputBuffer.addLine("Input sent: $userInput", "running")
                            else outputBuffer.addLine("Failed to send input (exit $exitVal)", "error")
                        }
                    }.onFailure {
                        when (it) {
                            is TimeoutCancellationException ->
                                outputBuffer.addLine("Timeout sending input: $userInput", "error")

                            else ->
                                outputBuffer.addLine("Failed to send input: ${it.message}", "error")
                        }
                    }
                }
            }

            // xx =================================================================================================
            // xx            >               ------------ Run the container process --------------------          <
            // xx =================================================================================================
            val arcanaPath = "${System.getProperty("user.home")}/Desktop/arcana"
            val repoPath = workspace.getPath()

            val dockerCmd = ContainerCommandBuilder.buildRunCommandString(
                containerId,
                dataMounts = listOf("$arcanaPath:/app/arcana:ro", "$repoPath:/app/repo"),
                additionalArgs = listOf("--entrypoint", "bash", "-w", "/app/arcana")
            ) + " -c \"python /app/arcana/$scriptName --path /app/repo --api ${message.openaiApiKey}${message.script?.let { " $it" } ?: ""}\""
//                additionalArgs = listOf("--entrypoint", "bash", "-w", "/app/arcana")
//            ) + " -c \"python /app/arcana/$scriptName --path /app/repo${scriptOptions?.let { " $it" } ?: ""}\""

            val logFile = File(resolveLogDir(), "arcana-$containerId.log")
            val exitCode = dockerCmd.shell(logFile = logFile) { line, isErr ->
                println("[ON_LINE_RECEIVED][${System.currentTimeMillis()}] isErr=$isErr, line_len=${line.length}")
                when {
                    isErr -> outputBuffer.addLine(line.trim(), "error") // .also { println("[ startContainer - isErr ] - adding to buffer") }
                    line.contains("`````INPUT") -> outputBuffer.addLine(line, "input_needed")
                        .also { println("[ startContainer - input needed ] - adding to buffer, after requesting for user input") }

                    else -> outputBuffer.addLine(line, "running") // .also { println("[ startContainer - else ]   - adding to buffer $line") }
                }
            }
            inputJob.cancelAndJoin()
            outputBuffer.close() // graceful close, with more aloha
            // xx ==========================================================================================

            sendContainerResponse(messageId, "exited", "Analysis exited with code $exitCode")
            activeContainers.remove(clientId)
        }.onFailure {
            when (it) {
                is CancellationException -> outputBuffer.addLine("Container timed out", "error")
                else -> {
                    application.log.error("[Container-$clientId] Error: ${it.message}", it)
                    runCatching { outputBuffer.close() }
                    sendContainerResponse(messageId, "error", "Container error: ${it.message}")
                }
            }
            activeContainers.remove(clientId)
        }
    }

    containerJob.invokeOnCompletion { activeContainers.remove(clientId) }
}

suspend fun stopContainerInternal(clientId: String): Boolean =
    activeContainers[clientId]?.let { session ->
        ContainerCommandBuilder.buildStopCommand(session.containerId).shell()
        session.job.cancelAndJoin()
        activeContainers.remove(clientId)
        true
    } ?: false

private suspend fun WebSocketServerSession.sendContainerInput(
    clientId: String,
    messageId: String,
    input: String
) {
    activeContainers[clientId]?.inputFlow?.emit(input) ?: sendContainerResponse(messageId, "error", "No active container found")
    sendContainerResponse(messageId, "running", "Input queued")
}

// ----------------------------------------------------------
// COMMAND BUILDER
// ----------------------------------------------------------
object ContainerCommandBuilder {
    private const val DEFAULT_IMAGE = "python-client"
    private const val DEFAULT_MEMORY = "512m"
    private const val DEFAULT_CPU = "0.5"

    fun buildRunCommandString(
        containerId: String,
        dataMounts: List<String>,
        memory: String = DEFAULT_MEMORY,
        cpus: String = DEFAULT_CPU,
        additionalArgs: List<String> = emptyList()
    ): String = listOf(
        "docker", "run", "--rm", "-i",
        "--name", "python-client-$containerId",
        "--memory", memory,
        "--cpus", cpus,
        "--network", "host"
    ).toMutableList().apply {
        dataMounts.forEach { mount -> addAll(listOf("-v", mount)) }
        addAll(additionalArgs)
        add(DEFAULT_IMAGE)
    }.joinToString(" ")

    fun buildStopCommand(containerId: String) =
        "docker stop python-client-$containerId"

    fun buildInputCommand(containerId: String, input: String) =
        """docker exec -i python-client-$containerId sh -c 'echo "$input" > /proc/1/fd/0'"""
}
