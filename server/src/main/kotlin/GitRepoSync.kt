package net.sdfgsdfg

import io.ktor.server.application.log
import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.server.websocket.application
import io.ktor.server.websocket.sendSerialized
import kotlinx.atomicfu.AtomicInt
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val REPO_BASE_DIR = "~/Desktop/server_repos"
private const val MAX_REPOS = 10
private const val MAX_REPO_SIZE_GB = 10
val activeGitOperations = ConcurrentHashMap<String, Job>()

// TODO: Move below to /model/
@Serializable
data class GitHubRepoData(
    val repoId: Long? = null,
    val name: String = "",
    val owner: String = "",
    val url: String = "",
    val branch: String? = null
)

@Serializable
data class GitHubRepoSelectMessage(
    val type: String? = null,
    val messageId: String? = null,
    val repoData: GitHubRepoData? = null,
    val accessToken: String? = null,
    val clientTimestamp: Long? = null
)

@Serializable
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

    if (repoData == null) { // If no repo data is passed, respond and bail out
        sendRepoResponse(messageId, "error", "Repository data is missing")
        return
    }

    // Cancel any existing Git operation for this client, then launch a new one
    activeGitOperations[clientId]?.cancelAndJoin()
    activeGitOperations[clientId] = launch(Dispatchers.IO) {
        val workspaceId = "${repoData.owner}_${repoData.name}_${UUID.randomUUID().toString().take(8)}"

        // Check repository state with our enhanced function
        when (repoManager.checkRepoState(repoData.owner, repoData.name)) {
            // Valid repository exists → Pull latest changes
            RepositoryManager.RepoState.Valid -> {
                sendRepoResponse(messageId, "cloning", "Repository already exists, syncing latest changes...", workspaceId, 50)
                try {
                    repoManager.pullRepository(repoData.owner, repoData.name, repoData.branch)
                    WorkspaceTracker.trackWorkspace(clientId, repoData.owner, repoData.name, workspaceId)
                    sendRepoResponse(messageId, "success", "Repository synchronized successfully", workspaceId, 100)
                } catch (e: CancellationException) {
                    // Just ignore cancellations
                } catch (e: Throwable) {
                    application.log.error("[WS-$clientId] Pull failed: ${e.message}", e)
                    sendRepoResponse(messageId = messageId, status = "error", message = errorMessageFor(e))
                }
                return@launch
            }

            // Invalid or missing repository → Clone fresh
            else -> {
                // New repository => clone with progress
                val progress = atomic(0)
                val progressJob = launch {
                    while (isActive) {
                        sendRepoResponse(messageId, "cloning", "Cloning repository...", workspaceId, progress.value)
                        delay((44..250).random().milliseconds)
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
                    WorkspaceTracker.trackWorkspace(clientId, repoData.owner, repoData.name, workspaceId)
                    sendRepoResponse(messageId, "success", "Repository cloned successfully", workspaceId, 100)
                } catch (e: CancellationException) {
                    progress.value = 0
                    progressJob.cancelAndJoin()
                    sendRepoResponse(messageId, "error", "Git operation cancelled")
                } catch (e: Throwable) {
                    application.log.error("[WS-$clientId] Clone failed: ${e.message}", e)
                    progress.value = 0
                    progressJob.cancelAndJoin()
                    sendRepoResponse(messageId, "error", errorMessageFor(e))
                }
            }
        }
    }.apply {
        invokeOnCompletion { activeGitOperations.remove(clientId) }
    }
}

private suspend fun WebSocketServerSession.sendRepoResponse(
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

    e.message?.contains("destination path", ignoreCase = true) == true &&
            e.message?.contains("already exists", ignoreCase = true) == true ->
        "Repository directory issue. We'll automatically clean up and retry."

    else -> "Operation failed: ${e.message ?: "Unknown error"}"
}


// -------------------------------------
// 2) Repository Manager + clone/pull
// -------------------------------------
class RepositoryManager {
    private val baseDir = File(REPO_BASE_DIR.replace("~", System.getProperty("user.home"))).apply { mkdirs() }
    private val recentRepos = mutableListOf<String>()

    // single state mutex (never re-enter)
    private val repoMutex = Mutex()

    // global op queue: at most MAX_REPOS git ops in-flight; others wait
    private val opSemaphore = Semaphore(MAX_REPOS)

    private fun getRepoPath(owner: String, name: String): File = File(baseDir, "${owner}_$name")

    sealed class RepoState {
        data object Valid : RepoState()
        data object Invalid : RepoState()
        data object Missing : RepoState()
    }

    fun checkRepoState(owner: String, name: String): RepoState {
        val repoDir = getRepoPath(owner, name)
        return when {
            repoDir.exists() && File(repoDir, ".git").exists() -> RepoState.Valid
            repoDir.exists() -> RepoState.Invalid
            else -> RepoState.Missing
        }
    }

    private suspend inline fun <T> withOpPermit(crossinline block: suspend () -> T): T {
        opSemaphore.acquire()
        return try {
            block()
        } finally {
            opSemaphore.release()
        }
    }

    // Clean invalid repository directory for fresh clone
    private suspend fun prepareRepoDirectory(owner: String, name: String): Boolean = withContext(Dispatchers.IO) {
        val repoDir = getRepoPath(owner, name)
        when (checkRepoState(owner, name)) {
            RepoState.Invalid -> {
                println("🧹 Cleaning invalid repository directory: ${repoDir.absolutePath}")
                repoDir.deleteRecursively() && repoDir.mkdirs()
            }

            RepoState.Missing -> repoDir.mkdirs()
            else -> true
        }
    }

    suspend fun cloneRepository(
        owner: String,
        name: String,
        url: String,
        branch: String?,
        accessToken: String?,
        progressTracker: AtomicInt
    ) = withContext(Dispatchers.IO) {
        withOpPermit {
            val repoDir = getRepoPath(owner, name)

            // Ensure capacity AND reserve the target directory under a single lock
            repoMutex.withLock {
                val count = baseDir.listFiles()?.count { it.isDirectory } ?: 0
                if (count >= MAX_REPOS) rotateOldestRepository()
                if (!prepareRepoDirectory(owner, name)) throw Exception("Failed to prepare repository directory")
            }

            val cloneUrl = accessToken?.takeIf { url.startsWith("https://") }
                ?.let { url.replace("https://", "https://$it@") }
                ?: url

            val branchArg = branch?.let { "-b $it" } ?: ""
            val sizeLimitExceeded = atomic(false)

            // Monitor size from background
            val monitorJob = launch {
                while (isActive) {
                    if (repoDir.exists() && getRepoSizeGB(owner, name) > MAX_REPO_SIZE_GB) {
                        sizeLimitExceeded.value = true
                        break
                    }
                    delay(787.milliseconds)
                }
            }

            try {
                withTimeout(300.seconds) {
                    val exitCode = "git clone --progress $branchArg $cloneUrl ${repoDir.absolutePath}".shell(
//                    val exitCode = """GIT_LFS_SKIP_SMUDGE=1 git -c filter.lfs.smudge=false -c filter.lfs.required=false clone --progress --no-checkout $branchArg $cloneUrl ${repoDir.absolutePath}""".shell(
                        onLine = { line, _ ->
                            parseLineForProgress(line, progressTracker)

                            // Monitor size in real-time
                            if (sizeLimitExceeded.value) throw Exception("Repository size limit exceeded. Operation aborted.")
                        },
                    )
                    if (exitCode != 0) throw Exception("Git clone failed with exit code $exitCode")
                }
            } catch (e: Throwable) {
                // Cleanup reserved directory on clone failure to avoid leaking a slot
                runCatching { repoDir.deleteRecursively() }
                throw e
            } finally {
                monitorJob.cancel()
            }

            // If successful, add to recents
            repoMutex.withLock {
                recentRepos.remove("${owner}_$name")
                recentRepos.add("${owner}_$name")
            }
        }
    }

    suspend fun pullRepository(owner: String, name: String, branch: String?) = withContext(Dispatchers.IO) {
        withOpPermit {
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
    }

    private fun repoDirs(): List<File> = baseDir.listFiles()?.filter(File::isDirectory) ?: emptyList()
    private fun birthMillis(p: File): Long? = runCatching {
        Files.readAttributes(p.toPath(), BasicFileAttributes::class.java)
            .creationTime().toMillis()
    }.getOrNull()?.takeIf { it > 0L }

    private fun addedAtMillis(dir: File): Long {
        val git = File(dir, ".git")
        // Prefer real birth time; fall back to .git birth; then .git mtime; then dir mtime.
        return birthMillis(dir)
            ?: birthMillis(git)
            ?: (if (git.exists()) git.lastModified() else dir.lastModified())
    }

    private fun rotateOldestRepository() {
        val victim = repoDirs().minByOrNull { addedAtMillis(it) } ?: return
        recentRepos.remove(victim.name)  // keep in‑mem index coherent if you still show it anywhere
        val deleted = victim.deleteRecursively()

        if (!deleted) {
            log("Failed to delete repository directory: ${victim.absolutePath}", resolveLogDir())
        }
    }

    private suspend fun getRepoSizeGB(owner: String, name: String): Double {
        val repoDir = getRepoPath(owner, name)
        if (!repoDir.exists()) return 0.0

        val command = if (isOSX()) {
            "du -sk ${repoDir.absolutePath} | cut -f1"
        } else {
            "du -sb ${repoDir.absolutePath} | cut -f1"
        }
        val sizeBytes = command.shell().toString().trim().toLongOrNull()?.let {
            if (isOSX()) it * 1024L else it
        } ?: 0L
        return sizeBytes / (1024.0 * 1024.0 * 1024.0)
    }
}

private fun parseLineForProgress(line: String, progressTracker: AtomicInt) {
    val patterns = listOf(
        ".*Enumerating objects:\\s*(\\d+)% .*".toRegex(),
        ".*Counting objects:\\s*(\\d+)% .*".toRegex(),
        ".*Compressing objects:\\s*(\\d+)% .*".toRegex(),
        ".*Receiving objects:\\s*(\\d+)% .*".toRegex(),
        ".*Resolving deltas:\\s*(\\d+)% .*".toRegex()
    )

    for (regex in patterns) {
        regex.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { pct ->
            progressTracker.value = pct
            return
        }
    }
}

/**
 * Simple tracker for client workspaces.
 * Keeps track of which repository is currently selected for each client.
 */
object WorkspaceTracker {
    // Map of clientId -> workspace info
    private val clientWorkspaces = ConcurrentHashMap<String, WorkspaceInfo>()

    data class WorkspaceInfo(
        val owner: String,
        val name: String,
        val workspaceId: String
    ) {
        fun getPath(): String = System.getProperty("user.home") + "/Desktop/server_repos/${owner}_${name}"
    }

    /**
     * Track a repository selection for a client
     */
    fun trackWorkspace(clientId: String, owner: String, name: String, workspaceId: String) {
        clientWorkspaces[clientId] = WorkspaceInfo(owner, name, workspaceId)
    }

    /**
     * Get current workspace for a client, if any
     */
    fun getCurrentWorkspace(clientId: String): WorkspaceInfo? = clientWorkspaces[clientId]

    fun removeClient(clientId: String) {
        clientWorkspaces.remove(clientId)
    }
}

/**
 * Remove workspace tracking when client disconnects
 */
fun isOSX(): Boolean = System.getProperty("os.name").lowercase().run { contains("mac") || contains("darwin") }
