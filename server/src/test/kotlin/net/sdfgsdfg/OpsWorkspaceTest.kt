package net.sdfgsdfg

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class OpsWorkspaceTest {
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
}
