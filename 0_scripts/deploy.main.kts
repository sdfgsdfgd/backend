#!/usr/bin/env kotlin

import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Socket
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

val app: String = "backend"
val service: String = "backend.service"
val port: Int = 80
val javaHome: String = "/usr/lib/jvm/java-21-openjdk-amd64"
val root: File = File(".").canonicalFile
val logs: File = root.resolve("0_scripts/logs").apply { mkdirs() }
val deployLog: File = logs.resolve("deploy.log").apply { if (!exists()) createNewFile() }
val buildLog: File = logs.resolve("build.log").apply { if (!exists()) createNewFile() }
val appLog: File = logs.resolve("app.log").apply { if (!exists()) createNewFile() }
val lockFile: File = logs.resolve("deploy.lock")
val bin: String = root.resolve("server/build/install/server/bin/server").canonicalPath
val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

data class Result(val code: Int, val out: String)

fun now(): String = LocalDateTime.now().format(timeFormat)

fun log(mark: String, message: String, file: File? = deployLog) {
    val line = "$mark $message"
    println(line)
    file?.appendText("[${now()}] $line\n")
}

fun run(command: String, file: File? = deployLog, check: Boolean = true, quiet: Boolean = false): Result {
    if (!quiet) log("▸", command, file)
    val p = ProcessBuilder("bash", "-lc", command)
        .directory(root)
        .redirectErrorStream(true)
        .start()
    val out = StringBuilder()
    p.inputStream.bufferedReader().useLines { lines ->
        lines.forEach {
            if (!quiet) println("│ $it")
            out.appendLine(it)
            file?.appendText("│ $it\n")
        }
    }
    val code = p.waitFor()
    if (!quiet) log(if (code == 0) "✓" else "✗", "exit=$code", file)
    if (check && code != 0) error("exit=$code: $command")
    return Result(code, out.toString())
}

fun q(command: String): String = run(command, file = null, check = false, quiet = true).out.trim()

fun fail(s: String): Nothing {
    log("✗", s)
    exitProcess(1)
}

fun portPid(): String? = q("(lsof -tiTCP:$port -sTCP:LISTEN 2>/dev/null || true) | head -n1").takeIf { it.isNotBlank() }

fun execStart(): String? = run("systemctl show -p ExecStart --value $service 2>/dev/null", null, check = false, quiet = true)
    .takeIf { it.code == 0 }
    ?.out
    ?.trim()
    ?.takeIf { it.isNotBlank() }

fun serviceMode(): String = execStart()?.let {
    when {
        "server/build/install/server/bin/server" in it -> "runtime"
        "deploy.main.kts" in it -> "legacy"
        else -> "other"
    }
} ?: "none"

fun insideBackendService(): Boolean = runCatching { File("/proc/self/cgroup").readText().contains(service) }.getOrDefault(false)

fun portOpen(): Boolean = runCatching {
    Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1_000) }
}.isSuccess

fun waitPort(seconds: Long = 30) {
    log("◆", "waiting for 127.0.0.1:$port")
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
    while (System.nanoTime() < deadline) {
        if (portOpen()) return log("✓", "$app is listening on port $port")
        Thread.sleep(1_000)
    }
    fail("$app did not open port $port within ${seconds}s")
}

fun stopPort() {
    val pid = portPid() ?: return log("⚠", "$app is not listening on port $port")
    log("◆", "stopping $app pid=$pid")
    run("kill -15 $pid", check = false)
    repeat(20) {
        if (run("ps -p $pid -o pid= >/dev/null 2>&1", null, check = false, quiet = true).code != 0) {
            log("✓", "stopped $app pid=$pid")
            return
        }
        Thread.sleep(1_000)
    }
    log("⚠", "graceful stop timed out; sending SIGKILL to pid=$pid")
    run("kill -9 $pid", check = false)
}

fun foreground(): Nothing {
    if (!File(bin).exists()) fail("Missing runtime script: $bin. Run deploy first.")
    log("◆", "starting $app in foreground: $bin", appLog)
    ProcessBuilder(bin)
        .directory(root)
        .inheritIO()
        .also {
            it.environment().putIfAbsent("JAVA_HOME", javaHome)
            it.environment()["PATH"] = "$javaHome/bin:${System.getenv("PATH").orEmpty()}"
        }
        .start()
        .waitFor()
        .let(::exitProcess)
}

fun systemctl(action: String) = run("sudo systemctl --no-ask-password $action $service")

fun gradle(tasks: String) = run("./gradlew $tasks --warning-mode all", buildLog)

fun syncGit() = run(
    """
    set -euo pipefail
    orig_head=${'$'}(git rev-parse HEAD)
    dirty=0
    restore() {
      git rebase --abort >/dev/null 2>&1 || true
      git reset --hard "${'$'}orig_head"
      if [ "${'$'}dirty" -eq 1 ]; then git stash pop --index; fi
    }
    if [ -n "${'$'}(git status --porcelain)" ]; then dirty=1; fi
    if [ "${'$'}dirty" -eq 1 ]; then git stash push -u -m "autostash:$app ${'$'}(date +%s)"; fi
    git fetch origin main --prune --quiet
    set -- ${'$'}(git rev-list --left-right --count HEAD...refs/remotes/origin/main)
    local_ahead=${'$'}1
    remote_ahead=${'$'}2
    if [ "${'$'}remote_ahead" -gt 0 ]; then
      echo "Git sync: rebasing ${'$'}remote_ahead remote commits; local ahead=${'$'}local_ahead."
      if ! git rebase refs/remotes/origin/main; then
        restore || true
        exit 1
      fi
    else
      echo "Git sync: already up to date; local ahead=${'$'}local_ahead."
    fi
    if [ "${'$'}dirty" -eq 1 ] && ! git stash pop --index; then restore || true; exit 1; fi
    """.trimIndent(),
    buildLog,
)

fun withLock(block: () -> Unit) {
    RandomAccessFile(lockFile, "rw").channel.use { channel ->
        val lock = channel.tryLock() ?: fail("Another deploy is already running: ${lockFile.absolutePath}")
        lock.use { block() }
    }
}

fun installAndForeground(): Nothing {
    log("◆", "using foreground process mode")
    stopPort()
    gradle(":server:installDist")
    foreground()
}

fun deploy() = withLock {
    val start = System.nanoTime()
    val mode = serviceMode()
    log("◆", "deploying $app")
    listOf(
        "mode" to mode,
        "head" to q("git rev-parse --short HEAD 2>/dev/null || true"),
    ).forEach { (name, value) -> log(" ", "${name.padEnd(12)} $value") }
    syncGit()
    gradle(":core:jvmTest :server:check")

    when (mode) {
        "runtime" -> {
            log("✓", "verification passed; runtime systemd unit detected")
            systemctl("stop")
            gradle(":server:installDist")
            systemctl("start")
            waitPort()
        }
        "legacy" -> {
            if (!insideBackendService()) {
                fail("$service still self-deploys through deploy.main.kts. Install the runtime unit before detached deploy.")
            }
            installAndForeground()
        }
        "other" -> fail("$service has an unsupported ExecStart: ${execStart()}")
        else -> installAndForeground()
    }

    log("✓", "deploy complete in ${(System.nanoTime() - start) / 1_000_000}ms")
}

fun status() {
    log("◆", "$app status")
    listOf(
        "project" to root.toString(),
        "head" to q("git rev-parse --short HEAD 2>/dev/null || true").ifBlank { "unknown" },
        "mode" to serviceMode(),
        "exec" to (execStart() ?: "unavailable"),
        "port:$port" to (portPid()?.let { "listening pid=$it" } ?: "closed"),
    ).forEach { (name, value) -> log(" ", "${name.padEnd(12)} $value") }
}

fun dispatch(command: String) {
    val mode = serviceMode()
    when (command) {
        "deploy", "" -> deploy()
        "start" -> when (mode) {
            "runtime" -> { systemctl("start"); waitPort() }
            "other" -> fail("$service has an unsupported ExecStart: ${execStart()}")
            else -> foreground()
        }
        "stop" -> when (mode) {
            "runtime" -> systemctl("stop")
            "other" -> fail("$service has an unsupported ExecStart: ${execStart()}")
            else -> stopPort()
        }
        "restart" -> when (mode) {
            "runtime" -> { systemctl("restart"); waitPort() }
            "other" -> fail("$service has an unsupported ExecStart: ${execStart()}")
            else -> { stopPort(); foreground() }
        }
        "status" -> status()
        "verify" -> gradle("clean build installDist")
        else -> fail("Usage: ./0_scripts/deploy.main.kts [deploy|start|stop|restart|status|verify]")
    }
}

val scriptExit = runCatching { dispatch(args.firstOrNull() ?: "deploy") }
    .fold(
        onSuccess = { 0 },
        onFailure = {
            log("✗", it.message ?: it::class.qualifiedName.orEmpty())
            1
        },
    )
if (scriptExit != 0) exitProcess(scriptExit)
