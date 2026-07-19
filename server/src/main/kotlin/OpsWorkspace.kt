package net.sdfgsdfg

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.sdfgsdfg.data.model.OpsRepositoryDto
import net.sdfgsdfg.data.model.OpsViewerDto
import net.sdfgsdfg.data.model.OpsWorkspaceActionDto
import net.sdfgsdfg.data.model.OpsWorkspaceCommandDto
import net.sdfgsdfg.data.model.OpsWorkspaceEventDto
import net.sdfgsdfg.data.model.OpsWorkspaceEventKindDto
import net.sdfgsdfg.data.model.OpsWorkspaceEventStatusDto
import net.sdfgsdfg.data.model.canRunSessions
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

internal const val LOCAL_ARCANA_REPOSITORY_ID = -1L

internal data class OpsSocketPrincipal(
    val viewer: OpsViewerDto,
    val githubToken: String?,
)

internal fun interface OpsWorkspaceCommandHandler {
    suspend fun handle(
        principal: OpsSocketPrincipal,
        command: OpsWorkspaceCommandDto,
        emit: suspend (OpsWorkspaceEventDto) -> Unit,
    )
}

internal class OpsWorkspaceService(
    private val http: HttpClient,
    private val repositories: RepositoryManager = RepositoryManager(),
    private val localArcana: File = arcanaRepo,
) : OpsWorkspaceCommandHandler {
    private data class WorkspaceRepository(
        val value: OpsRepositoryDto,
        val cloneUrl: String? = null,
        val localPath: File? = null,
    )
    private data class CachedRepositories(val expiresAtMs: Long, val values: List<WorkspaceRepository>)

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, CachedRepositories>()

    override suspend fun handle(
        principal: OpsSocketPrincipal,
        command: OpsWorkspaceCommandDto,
        emit: suspend (OpsWorkspaceEventDto) -> Unit,
    ) {
        when (command.action) {
            OpsWorkspaceActionDto.LIST_REPOSITORIES -> listRepositories(principal, command, emit)
            OpsWorkspaceActionDto.SELECT_REPOSITORY -> selectRepository(principal, command, emit)
        }
    }

    private suspend fun listRepositories(
        principal: OpsSocketPrincipal,
        command: OpsWorkspaceCommandDto,
        emit: suspend (OpsWorkspaceEventDto) -> Unit,
    ) {
        emit(command.event(OpsWorkspaceEventKindDto.REPOSITORIES, OpsWorkspaceEventStatusDto.LOADING, "Loading repositories…"))
        runCatching { repositories(principal) }
            .onSuccess { values ->
                emit(
                    command.event(
                        kind = OpsWorkspaceEventKindDto.REPOSITORIES,
                        status = OpsWorkspaceEventStatusDto.READY,
                        message = "${values.size} repositories ready",
                        repositories = values.map(WorkspaceRepository::value),
                    ),
                )
            }
            .onFailure { error ->
                emit(command.event(OpsWorkspaceEventKindDto.REPOSITORIES, OpsWorkspaceEventStatusDto.ERROR, error.safeMessage()))
            }
    }

    private suspend fun selectRepository(
        principal: OpsSocketPrincipal,
        command: OpsWorkspaceCommandDto,
        emit: suspend (OpsWorkspaceEventDto) -> Unit,
    ) {
        val repositoryId = command.repositoryId ?: run {
            emit(command.event(OpsWorkspaceEventKindDto.SYNC, OpsWorkspaceEventStatusDto.ERROR, "Repository id is missing"))
            return
        }
        // The trusted local checkout is an admin capability, not a remote-authenticated repository.
        val local = localArcana.takeIf { principal.viewer.canRunSessions() }?.let(::localRepository)
        val repository = local?.takeIf { it.value.id == repositoryId }
            ?: runCatching { repositories(principal).firstOrNull { it.value.id == repositoryId } }
                .getOrElse { error ->
                    emit(command.event(OpsWorkspaceEventKindDto.SYNC, OpsWorkspaceEventStatusDto.ERROR, error.safeMessage(), repositoryId = repositoryId))
                    return
                }
            ?: run {
                emit(command.event(OpsWorkspaceEventKindDto.SYNC, OpsWorkspaceEventStatusDto.ERROR, "Repository is not available to this viewer", repositoryId = repositoryId))
                return
            }
        val value = repository.value
        val workspaceId = stableWorkspaceId(principal.viewer.userId, value.id)
        emit(
            command.event(
                OpsWorkspaceEventKindDto.SYNC,
                OpsWorkspaceEventStatusDto.INITIALIZING,
                "Preparing ${value.name}…",
                progress = 0,
                repositoryId = value.id,
                workspaceId = workspaceId,
            ),
        )

        runCatching {
            val localPath = repository.localPath?.canonicalFile
            if (localPath != null) {
                require(localPath.isDirectory && localPath.resolve(".git").exists()) {
                    "Local Arcana repository is missing at ${localPath.path}"
                }
                emit(command.syncing(value.id, workspaceId, 50, "Opening local checkout…"))
                WorkspaceTracker.trackWorkspace(
                    principal.viewer.userId, value.owner, value.name, workspaceId, value.id, localPath,
                )
                return@runCatching
            }
            repositories.withRepositoryLock(value.owner, value.name) {
                when (repositories.checkRepoState(value.owner, value.name)) {
                    RepositoryManager.RepoState.Valid -> {
                        emit(command.syncing(value.id, workspaceId, 50, "Repository exists · pulling latest changes…"))
                        repositories.pullRepository(value.owner, value.name, value.defaultBranch, principal.githubToken)
                    }

                    RepositoryManager.RepoState.Invalid,
                    RepositoryManager.RepoState.Missing,
                    -> coroutineScope {
                        val progress = atomic(0)
                        val reporter = launch {
                            while (isActive) {
                                val current = progress.value.coerceIn(0, 99)
                                emit(command.syncing(value.id, workspaceId, current, "Cloning repository $current%"))
                                delay(140)
                            }
                        }
                        try {
                            repositories.cloneRepository(
                                owner = value.owner,
                                name = value.name,
                                url = requireNotNull(repository.cloneUrl),
                                branch = value.defaultBranch,
                                accessToken = principal.githubToken,
                                progressTracker = progress,
                            )
                        } finally {
                            reporter.cancelAndJoin()
                        }
                    }
                }
                WorkspaceTracker.trackWorkspace(principal.viewer.userId, value.owner, value.name, workspaceId, value.id)
            }
        }.onSuccess {
            emit(
                command.event(
                    OpsWorkspaceEventKindDto.SYNC,
                    OpsWorkspaceEventStatusDto.SYNCHRONIZED,
                    if (repository.localPath == null) "${value.name} synchronized ✨" else "${value.name} ready ✨",
                    progress = 100,
                    repositoryId = value.id,
                    workspaceId = workspaceId,
                ),
            )
        }.onFailure { error ->
            emit(
                command.event(
                    OpsWorkspaceEventKindDto.SYNC,
                    OpsWorkspaceEventStatusDto.ERROR,
                    error.safeMessage(),
                    repositoryId = value.id,
                    workspaceId = workspaceId,
                ),
            )
        }
    }

    private suspend fun repositories(principal: OpsSocketPrincipal): List<WorkspaceRepository> {
        // Admin identity contributes Arcana before the optional remote repository source.
        val local = localArcana.takeIf { principal.viewer.canRunSessions() }?.let(::localRepository)
        val token = principal.githubToken ?: return listOfNotNull(local).ifEmpty {
            error("Reconnect GitHub to load repositories")
        }
        val now = System.currentTimeMillis()
        val cacheKey = repositoryCacheKey(principal.viewer.userId, token)
        val github = cache[cacheKey]?.takeIf { it.expiresAtMs > now }?.values
            ?: fetchRepositories(token).also { cache[cacheKey] = CachedRepositories(now + 60_000L, it) }
        return listOfNotNull(local) + github
    }

    private suspend fun fetchRepositories(token: String) = withContext(Dispatchers.IO) {
        buildList {
            for (page in 1..10) {
                val request = HttpRequest.newBuilder(
                    URI.create("https://api.github.com/user/repos?per_page=100&page=$page&sort=updated&direction=desc&affiliation=owner,collaborator,organization_member"),
                )
                    .timeout(Duration.ofSeconds(12))
                    .header("Accept", "application/vnd.github+json")
                    .header("Authorization", "Bearer $token")
                    .header("User-Agent", "ops-dashboard")
                    .GET()
                    .build()
                val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) error("GitHub repository lookup failed with ${response.statusCode()}")
                if (page == 1 && response.headers().firstValue("x-oauth-scopes").orElse("")
                        .split(',').none { it.trim() == "repo" }
                ) error("Reconnect GitHub to grant private repository access")
                val pageValues = (json.parseToJsonElement(response.body()) as? JsonArray)
                    ?.mapNotNull(::githubRepository)
                    ?: error("GitHub repository lookup returned invalid JSON")
                addAll(pageValues)
                if (pageValues.size < 100) break
            }
        }.distinctBy { it.value.id }
    }

    private fun githubRepository(element: kotlinx.serialization.json.JsonElement): WorkspaceRepository? {
        val value = element as? JsonObject ?: return null
        val id = value.number("id") ?: return null
        val name = value.text("name")?.takeIf(String::isSafeRepoSegment) ?: return null
        val owner = value["owner"]?.jsonObject?.text("login")?.takeIf(String::isSafeRepoSegment) ?: return null
        val fullName = value.text("full_name")?.takeIf { it == "$owner/$name" } ?: return null
        val cloneUrl = value.text("clone_url")?.takeIf(String::isSafeGithubCloneUrl) ?: return null
        return WorkspaceRepository(
            value = OpsRepositoryDto(
                id = id,
                name = name,
                owner = owner,
                fullName = fullName,
                description = value.text("description"),
                language = value.text("language"),
                stars = value.int("stargazers_count") ?: 0,
                updatedAt = value.text("updated_at") ?: "1970-01-01T00:00:00Z",
                defaultBranch = value.text("default_branch")?.takeIf(String::isSafeGitRef) ?: "main",
                isPrivate = value.bool("private") ?: false,
            ),
            cloneUrl = cloneUrl,
        )
    }

    private fun localRepository(path: File) = WorkspaceRepository(
        value = OpsRepositoryDto(
            id = LOCAL_ARCANA_REPOSITORY_ID,
            name = "arcana",
            owner = "local",
            fullName = "local/arcana",
            description = "Local codebase comprehension and session engine",
            language = "Python",
            updatedAt = Instant.ofEpochMilli(path.lastModified().coerceAtLeast(0L)).toString(),
            defaultBranch = "main",
            isPrivate = true,
        ),
        localPath = path,
    )
}

private fun OpsWorkspaceCommandDto.event(
    kind: OpsWorkspaceEventKindDto,
    status: OpsWorkspaceEventStatusDto,
    message: String,
    progress: Int? = null,
    repositories: List<OpsRepositoryDto> = emptyList(),
    repositoryId: Long? = null,
    workspaceId: String? = null,
) = OpsWorkspaceEventDto(requestId, kind, status, message, progress, repositories, repositoryId, workspaceId)

private fun OpsWorkspaceCommandDto.syncing(repositoryId: Long, workspaceId: String, progress: Int, message: String) =
    event(OpsWorkspaceEventKindDto.SYNC, OpsWorkspaceEventStatusDto.SYNCING, message, progress, repositoryId = repositoryId, workspaceId = workspaceId)

private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
private fun JsonObject.number(key: String) = this[key]?.jsonPrimitive?.longOrNull
private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.intOrNull
private fun JsonObject.bool(key: String) = this[key]?.jsonPrimitive?.booleanOrNull

private fun stableWorkspaceId(viewerId: String, repositoryId: Long) = stableWorkspaceHash("$viewerId:$repositoryId")

internal fun repositoryCacheKey(viewerId: String, token: String) = "$viewerId:${stableWorkspaceHash(token)}"

private fun Throwable.safeMessage() = when {
    message?.contains("timed out", ignoreCase = true) == true -> "Repository synchronization timed out"
    message?.contains("size limit", ignoreCase = true) == true -> "Repository exceeds the 10 GB workspace limit"
    message?.startsWith("GitHub repository lookup failed") == true -> message!!
    message?.startsWith("Reconnect GitHub") == true -> message!!
    message?.startsWith("Local Arcana repository") == true -> message!!
    else -> "Repository synchronization failed"
}
