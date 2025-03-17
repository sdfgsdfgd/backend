package net.sdfgsdfg

import io.ktor.server.application.*
import io.ktor.server.websocket.*
import kotlinx.atomicfu.AtomicBoolean
import kotlinx.atomicfu.AtomicInt
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val REPO_BASE_DIR = "~/Desktop/server_repos"
private const val MAX_REPOS = 10
private const val MAX_REPO_SIZE_GB = 10
val activeGitOperations = ConcurrentHashMap<String, Job>()

data class GitHubRepoData(
    val repoId: Long? = null,
    val name: String = "",
    val owner: String = "",
    val url: String = "",
    val branch: String? = null
)

data class GitHubRepoSelectMessage(
    val type: String? = null,
    val messageId: String? = null,
    val repoData: GitHubRepoData? = null,
    val accessToken: String? = null,
    val clientTimestamp: Long? = null
)

data class GitHubRepoSelectResponse(
    val type: String = "workspace_select_github_response",
    val messageId: String,
    val status: String, // "success" | "error" | "cloning"
    val message: String,
    val workspaceId: String? = null,
    val progress: Int? = null,
    val serverTimestamp: Long
)

suspend fun WebSocketServerSession.handleGitHubRepoSelect(
    message: GitHubRepoSelectMessage,
    clientId: String,
    repoManager: RepositoryManager
) {
    val messageId = message.messageId ?: UUID.randomUUID().toString()
    val repoData = message.repoData

    // If no repo data is passed, respond and bail out
    if (repoData == null) {
        sendResponse(messageId, "error", "Repository data is missing")
        return
    }

    // Cancel any existing Git operation for this client, then launch a new one
    activeGitOperations[clientId]?.cancelAndJoin()
    activeGitOperations[clientId] = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
        val workspaceId = "${repoData.owner}_${repoData.name}_${UUID.randomUUID().toString().take(8)}"

        // Already cloned? => attempt pulling
        if (repoManager.repositoryExists(repoData.owner, repoData.name)) {
            sendResponse(messageId, "cloning", "Repository already exists, syncing latest changes...", workspaceId, 50)
            try {
                repoManager.pullRepository(repoData.owner, repoData.name, repoData.branch)
                sendResponse(messageId, "success", "Repository synchronized successfully", workspaceId, 100)
            } catch (e: CancellationException) {
                // Just ignore cancellations
            } catch (e: Throwable) {
                application.log.error("[WS-$clientId] Pull failed: ${e.message}", e)
                sendResponse(messageId = messageId, status = "error", message = errorMessageFor(e))
            }
            return@launch
        }

        // New repository => clone with progress
        val progress = atomic(0)
        val progressJob = launch {
            while (isActive) {
                sendResponse(messageId, "cloning", "Cloning repository...", workspaceId, progress.value)
                delay((100..450).random().milliseconds)
                if (progress.value >= 100) break
            }
        }

        try {
            repoManager.cloneRepository(
                owner = repoData.owner,
                name = repoData.name,
                url = repoData.url,
                branch = repoData.branch,
                accessToken = message.accessToken,
                progressTracker = progress
            )
            progress.value = 100
            progressJob.join()
            sendResponse(messageId, "success", "Repository cloned successfully", workspaceId, 100)
        } catch (e: CancellationException) {
            // Ignore cancellations
        } catch (e: Throwable) {
            application.log.error("[WS-$clientId] Clone failed: ${e.message}", e)
            progress.value = 0
            progressJob.cancelAndJoin()
            sendResponse(messageId, "error", errorMessageFor(e))
        }
    }.apply {
        invokeOnCompletion { activeGitOperations.remove(clientId) }
    }
}

private suspend fun WebSocketServerSession.sendResponse(
    messageId: String,
    status: String,
    message: String,
    workspaceId: String? = null,
    progress: Int? = null
) {
    println("📤 Sending WebSocket response: status=$status, progress=$progress, message=$message")
    sendSerialized(
        GitHubRepoSelectResponse(
            messageId = messageId,
            status = status,
            message = message,
            workspaceId = workspaceId,
            progress = progress,
            serverTimestamp = System.currentTimeMillis()
        )
    )
}

fun errorMessageFor(e: Throwable) = when {
    e.message?.contains("timed out", ignoreCase = true) == true ->
        "Operation timed out after 30 seconds. The repository might be too large or the connection too slow."

    e.message?.contains("size limit", ignoreCase = true) == true ->
        e.message ?: "Repository exceeds size limit."

    else -> "Operation failed: ${e.message ?: "Unknown error"}"
}

// ---

class RepositoryManager {
    private val baseDir = File(REPO_BASE_DIR.replace("~", System.getProperty("user.home"))).apply { mkdirs() }
    private val recentRepos = mutableListOf<String>()
    private val repoMutex = Mutex()  // For concurrency around rotating + removing repos

    fun getRepoPath(owner: String, name: String): File = File(baseDir, "${owner}_$name")

    fun repositoryExists(owner: String, name: String): Boolean =
        getRepoPath(owner, name).let { it.exists() && File(it, ".git").exists() }

    suspend fun cloneRepository(
        owner: String,
        name: String,
        url: String,
        branch: String?,
        accessToken: String?,
        progressTracker: AtomicInt? = null
    ) = withContext(Dispatchers.IO) {
        repoMutex.withLock {
            // If we already have too many, rotate
            if ((baseDir.listFiles()?.count { it.isDirectory } ?: 0) >= MAX_REPOS) rotateOldestRepository()
        }

        val repoDir = getRepoPath(owner, name)
        val cloneUrl = accessToken?.takeIf { url.startsWith("https://") }
            ?.let { url.replace("https://", "https://$it@") }
            ?: url

        val branchArg = branch?.let { "-b $it" } ?: ""
        val sizeLimitExceeded = atomic(false)

        // Start monitoring size asynchronously
        val monitorJob = launch {
            while (isActive) {
                if (repoDir.exists() && getRepoSizeGB(owner, name) > MAX_REPO_SIZE_GB) {
                    sizeLimitExceeded.value = true
                    break
                }
                delay(200.milliseconds)
            }
        }

        try {
            withTimeout(30.seconds) {
                gitCloneCommand(repoDir, cloneUrl, branchArg, owner, name, progressTracker, sizeLimitExceeded)
            }
        } finally {
            monitorJob.cancel()
        }

        // If successful, add to recents
        repoMutex.withLock {
            recentRepos.remove("${owner}_$name")
            recentRepos.add("${owner}_$name")
        }
    }

    suspend fun pullRepository(owner: String, name: String, branch: String?) = withContext(Dispatchers.IO + SupervisorJob()) {
        val repoDir = getRepoPath(owner, name)
        withTimeout(30.seconds) {
            branch?.let { "cd ${repoDir.absolutePath} && git checkout $it".shell() }
            "cd ${repoDir.absolutePath} && git pull".shell()
        }
        repoMutex.withLock {
            recentRepos.remove("${owner}_$name")
            recentRepos.add("${owner}_$name")
        }
    }

    fun getRepoSizeGB(owner: String, name: String): Double = getRepoPath(owner, name).let { repoDir ->
        if (!repoDir.exists()) return 0.0
        val command = if (isOSX()) {
            // macOS returns kilobytes, so multiply by 1024 => bytes
            "du -sk ${repoDir.absolutePath} | cut -f1"
        } else {
            // Linux returns bytes directly
            "du -sb ${repoDir.absolutePath} | cut -f1"
        }
        val sizeBytes = command.shell().trim().toLongOrNull()?.let {
            if (isOSX()) it * 1024L else it
        } ?: 0L
        sizeBytes / (1024.0 * 1024.0 * 1024.0)
    }

    private suspend fun rotateOldestRepository() = repoMutex.withLock {
        if (recentRepos.isNotEmpty()) {
            recentRepos.removeAt(0).split("_").takeIf { it.size >= 2 }?.let {
                deleteRepository(it[0], it[1])
            }
        } else {
            // If no history, delete oldest by modification time
            baseDir.listFiles()
                ?.filter { it.isDirectory }
                ?.minByOrNull { it.lastModified() }
                ?.deleteRecursively()
        }
    }

    fun deleteRepository(owner: String, name: String) {
        getRepoPath(owner, name).takeIf { it.exists() }?.deleteRecursively()
        recentRepos.remove("${owner}_$name")
    }
}

// Simple Git clone helper that parses progress
private fun gitCloneCommand(
    repoDir: File,
    cloneUrl: String,
    branchArg: String,
    owner: String,
    name: String,
    progressTracker: AtomicInt?,
    sizeLimitExceeded: AtomicBoolean
) {
    val patterns = listOf(
        "Receiving objects:\\s+(\\d+)%".toRegex(),
        "Receiving objects:\\s+(\\d+)% \\(\\d+/\\d+\\)".toRegex(),
        "remote: Counting objects:\\s+(\\d+)% \\(\\d+/\\d+\\)".toRegex(),
        "remote: Compressing objects:\\s+(\\d+)% \\(\\d+/\\d+\\)".toRegex(),
        "Resolving deltas:\\s+(\\d+)%".toRegex(),
        "Resolving deltas:\\s+(\\d+)% \\(\\d+/\\d+\\)".toRegex()
    )

    ProcessBuilder(
        "bash", "-c", "git clone --progress $branchArg $cloneUrl ${repoDir.absolutePath} 2>&1"
    ).redirectErrorStream(true)
        .start()
        .apply {
            inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    // Update progress if matches
                    patterns
                        .asSequence()
                        .mapNotNull { it.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() }
                        .firstOrNull()
                        ?.let { percent -> progressTracker?.value = percent }

                    // Stop the process if size limit is exceeded
                    if (sizeLimitExceeded.value) destroy()
                }
            }
            val exitCode = waitFor()
            if (exitCode != 0) throw Exception("Git clone failed with exit code $exitCode")
        }
}

fun isOSX(): Boolean = System.getProperty("os.name").lowercase().run {
    contains("mac") || contains("darwin")
}

// Inline shell helper
fun String.shell(): String = ProcessBuilder("bash", "-c", this).start().run {
    val output = inputStream.bufferedReader().readText()
    if (waitFor() != 0) throw Exception("Command failed: $this\nOutput: $output")
    output
}