package net.sdfgsdfg

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.sdfgsdfg.data.model.OPS_CAPABILITY_SESSIONS_RUN
import net.sdfgsdfg.data.model.OpsViewerDto
import net.sdfgsdfg.data.model.OpsWorkspaceActionDto
import net.sdfgsdfg.data.model.OpsWorkspaceCommandDto
import net.sdfgsdfg.data.model.OpsWorkspaceEventDto
import net.sdfgsdfg.data.model.OpsWorkspaceEventStatusDto
import java.io.File
import java.net.http.HttpClient
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OpsWorkspaceTest {
    @Test
    fun localArcanaIsAdminOnlyAndIndependentOfGithubAuthentication() = runBlocking {
        val root = createTempDirectory("ops-local-arcana").toFile()
        val arcana = root.resolve("arcana").apply {
            mkdirs()
            resolve(".git").mkdir()
        }
        val viewer = OpsViewerDto(
            userId = "kaan",
            displayName = "Kaan",
            role = "admin",
            capabilities = listOf(OPS_CAPABILITY_SESSIONS_RUN),
        )
        val service = OpsWorkspaceService(HttpClient.newHttpClient(), RepositoryManager(root.resolve("repositories")), arcana)

        suspend fun execute(principal: OpsSocketPrincipal, command: OpsWorkspaceCommandDto) = buildList<OpsWorkspaceEventDto> {
            service.handle(principal, command, ::add)
        }

        try {
            val admin = OpsSocketPrincipal(viewer, githubToken = null)
            val repositories = execute(admin, OpsWorkspaceCommandDto("list", OpsWorkspaceActionDto.LIST_REPOSITORIES)).last()
            assertEquals(OpsWorkspaceEventStatusDto.READY, repositories.status)
            assertEquals(listOf("arcana"), repositories.repositories.map { it.name })
            assertEquals(listOf(LOCAL_ARCANA_REPOSITORY_ID), repositories.repositories.map { it.id })

            val selection = execute(
                admin.copy(githubToken = "present-but-irrelevant"),
                OpsWorkspaceCommandDto("select", OpsWorkspaceActionDto.SELECT_REPOSITORY, LOCAL_ARCANA_REPOSITORY_ID),
            ).last()
            assertEquals(OpsWorkspaceEventStatusDto.SYNCHRONIZED, selection.status)
            assertEquals(arcana.canonicalPath, WorkspaceTracker.getCurrentWorkspace(viewer.userId)?.getPath())

            val guest = execute(
                OpsSocketPrincipal(OpsViewerDto(), githubToken = null),
                OpsWorkspaceCommandDto("guest", OpsWorkspaceActionDto.LIST_REPOSITORIES),
            ).last()
            assertEquals(OpsWorkspaceEventStatusDto.ERROR, guest.status)
            assertTrue(guest.repositories.isEmpty())
        } finally {
            WorkspaceTracker.removeClient(viewer.userId)
            root.deleteRecursively()
        }
    }

    @Test
    fun workspaceIdentityIsCompatibleConstrainedAndCollisionSafe() {
        val root = createTempDirectory("ops-workspace").toFile()
        try {
            val repositories = RepositoryManager(root)
            assertEquals("owner_repo", repositories.repositoryPath("owner", "repo").name)
            assertNotEquals(
                repositories.repositoryPath("owner_name", "repo"),
                repositories.repositoryPath("owner", "name_repo"),
            )
            assertFailsWith<IllegalArgumentException> { repositories.repositoryPath("..", "repo") }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sameRepositorySyncIsSerializedAndCredentialCacheKeysStayOpaque() = runBlocking {
        val root = createTempDirectory("ops-workspace").toFile()
        try {
            val repositories = RepositoryManager(root)
            val active = AtomicInteger()
            val maximum = AtomicInteger()
            coroutineScope {
                repeat(8) {
                    launch(Dispatchers.Default) {
                        repositories.withRepositoryLock("owner", "repo") {
                            maximum.updateAndGet { current -> maxOf(current, active.incrementAndGet()) }
                            delay(5)
                            active.decrementAndGet()
                        }
                    }
                }
            }
            assertEquals(1, maximum.get())

            val first = repositoryCacheKey("viewer", "credential-one")
            assertEquals(first, repositoryCacheKey("viewer", "credential-one"))
            assertNotEquals(first, repositoryCacheKey("viewer", "credential-two"))
            assertFalse("credential-one" in first)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun leastRecentlyUsedRotationKeepsAReusedRepository() {
        val root = createTempDirectory("ops-workspace").toFile()
        try {
            val repositories = RepositoryManager(root)
            val stale = root.resolve("stale").apply(File::mkdirs)
            val reused = root.resolve("reused").apply(File::mkdirs)
            assertTrue(stale.setLastModified(1_000))
            assertTrue(reused.setLastModified(2_000))

            repositories.evictLeastRecentlyUsedRepository()

            assertFalse(stale.exists())
            assertTrue(reused.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
