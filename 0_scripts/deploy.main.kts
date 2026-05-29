#!/usr/bin/env kotlin

import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Socket
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

val app: String = "backend"
val service: String = "backend.service"
val port: Int = 80
val postgresPort: Int = 5432
val javaHome: String = "/usr/lib/jvm/java-21-openjdk-amd64"
val qHost: String = "q"
val qArcanaDir: String = "~/Desktop/py/arcana"
val qArcanaTests: String = "z_tests_n_benchmarks/unit"
val arcanaIngestArtifactUrl: String = "/api/ops/artifacts/arcana-ingest.json"
val root: File = File(".").canonicalFile
val logs: File = root.resolve("0_scripts/logs").apply { mkdirs() }
val deployLog: File = logs.resolve("deploy.log").apply { if (!exists()) createNewFile() }
val deployHistory: File = logs.resolve("deploy-history.jsonl").apply { if (!exists()) createNewFile() }
val buildLog: File = logs.resolve("build.log").apply { if (!exists()) createNewFile() }
val appLog: File = logs.resolve("app.log").apply { if (!exists()) createNewFile() }
val lockFile: File = logs.resolve("deploy.lock")
val scriptPath: String = "0_scripts/deploy.main.kts"
val scriptFile: File = root.resolve(scriptPath)
val syncFromEnv: String = "BACKEND_DEPLOY_SYNC_FROM"
val dashboardWebArtifact: File = root.resolve("dashboard/web/build/dist/wasmJs/productionExecutable/dashboard-web.js")
val dashboardWebInputs: List<String> = listOf(
    "dashboard",
    "core/src/commonMain",
    "core/build.gradle.kts",
    "gradle/libs.versions.toml",
    "gradle.properties",
    "settings.gradle.kts",
    "build.gradle.kts",
)
val localDbCompose: File = root.resolve("ops/local/postgres.compose.yml")
val bin: String = root.resolve("server/build/install/server/bin/server").canonicalPath
val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
val dockerCompose: String by lazy {
    when {
        run("docker compose version", null, check = false, quiet = true).code == 0 -> "docker compose"
        run("docker-compose version", null, check = false, quiet = true).code == 0 -> "docker-compose"
        else -> fail("Docker Compose is required for local foreground deploy.")
    }
}
val runningOnQ: Boolean by lazy { q("hostname -s 2>/dev/null || hostname").equals(qHost, ignoreCase = true) }

data class Result(val code: Int, val out: String)

class ScriptFailure(message: String) : RuntimeException(message)

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

fun head(): String = q("git rev-parse HEAD 2>/dev/null || true")

fun fail(s: String): Nothing {
    log("✗", s)
    throw ScriptFailure(s)
}

fun String.shellQuote(): String = "'${replace("'", "'\\''")}'"

fun String.jsonString(): String = buildString {
    append('"')
    for (ch in this@jsonString) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(ch)
        }
    }
    append('"')
}

fun List<String>.shellPathspec(): String = joinToString(" ") { it.shellQuote() }

fun portPid(): String? = listOf(
    "lsof -tiTCP:$port -sTCP:LISTEN 2>/dev/null | head -n1",
    "ss -ltnp 'sport = :$port' 2>/dev/null | sed -n 's/.*pid=\\([0-9][0-9]*\\).*/\\1/p' | head -n1",
    "sudo ss -ltnp 'sport = :$port' 2>/dev/null | sed -n 's/.*pid=\\([0-9][0-9]*\\).*/\\1/p' | head -n1",
).firstNotNullOfOrNull { q(it).takeIf(String::isNotBlank) }

fun localDb(command: String, check: Boolean = true, quiet: Boolean = false): Result =
    run("$dockerCompose -f ${localDbCompose.absolutePath.shellQuote()} $command", check = check, quiet = quiet)

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

fun smoke(name: String, url: String, expect: String = "[ OK ]") {
    log("◆", "$name: $url")
    val result = run("curl -fsS --max-time 10 ${url.shellQuote()}", check = false, quiet = true)
    if (result.code != 0) fail("$name failed: exit=${result.code}")
    if (expect !in result.out) fail("$name failed: unexpected response: ${result.out.trim().take(200)}")
    log("✓", "$name passed")
}

fun localSmoke() {
    waitPort()
    smoke("local /test", "http://127.0.0.1:$port/test")
    smoke("local /metrics/security", "http://127.0.0.1:$port/metrics/security", "backend_request_event_class_total")
}

fun publicSmoke() {
    smoke("public smoke", "https://sdfgsdfg.net/test")
}

fun qRun(command: String, check: Boolean = true, quiet: Boolean = false): Result =
    run(if (runningOnQ) command else "ssh $qHost ${command.shellQuote()}", check = check, quiet = quiet)

fun arcanaSmoke() {
    log("◆", "q arcana pytest")
    val head = qRun("cd $qArcanaDir && git rev-parse --short HEAD", quiet = true).out.trim().ifBlank { "unknown" }
    val started = System.nanoTime()
    val result = qRun(
        """
        set -euo pipefail
        cd $qArcanaDir
        if [ ! -x .venv/bin/python ]; then
          python3 -m venv .venv
          .venv/bin/python -m pip install --upgrade pip
          .venv/bin/python -m pip install -r requirements.txt
        fi
        .venv/bin/python -m pip show coverage >/dev/null 2>&1 || .venv/bin/python -m pip install coverage
        .venv/bin/python -m coverage erase
        set +e
        .venv/bin/python -m coverage run --source=. --omit='z_tests_n_benchmarks/*' -m pytest $qArcanaTests -q
        rc=${'$'}?
        .venv/bin/python -m coverage report --format=total || true
        exit ${'$'}rc
        """.trimIndent(),
        check = false,
    )
    val durationMs = (System.nanoTime() - started) / 1_000_000
    val summary = result.out.lineSequence()
        .map(String::trim)
        .lastOrNull { it.contains(" passed") || it.contains(" failed") || it.contains(" error") }
        ?: "pytest exit=${result.code}"
    val status = if (result.code == 0) "OK" else "FAIL"
    val detail = "$summary on q @$head"
    val coveragePct = result.out.lineSequence()
        .mapNotNull { it.trim().removeSuffix("%").toDoubleOrNull()?.takeIf { pct -> pct in 0.0..100.0 } }
        .lastOrNull()
    val coverageField = coveragePct?.let { "\"coverage_pct\":${String.format(Locale.US, "%.1f", it)}" }
    val runFields = listOfNotNull(
        "\"label\":${qArcanaTests.jsonString()}",
        "\"status\":${status.jsonString()}",
        "\"duration_ms\":$durationMs",
        "\"detail\":${summary.jsonString()}",
        "\"url\":${arcanaIngestArtifactUrl.jsonString()}",
        coverageField,
    )
    val payload = listOfNotNull(
        "\"status\":${status.jsonString()}",
        "\"label\":\"q arcana unit pytest\"",
        "\"duration_ms\":$durationMs",
        "\"detail\":${detail.jsonString()}",
        "\"url\":${arcanaIngestArtifactUrl.jsonString()}",
        coverageField,
        "\"runs\":[{${runFields.joinToString(",")}}]",
    ).joinToString(prefix = "{", postfix = "}")
    qRun("curl -fsS -X POST http://127.0.0.1/api/ops/ingest/arcana -H 'Content-Type: application/json' --data-binary ${payload.shellQuote()}")
    if (result.code != 0) fail("q arcana pytest failed: $summary")
    log("✓", "q arcana pytest passed: $summary")
}

fun localTests() {
    gradle(":verifyServer :installServer")
    if (portOpen()) {
        log("◆", "using existing local backend for tests")
        localSmoke()
        return
    }
    startLocalDb()
    val process = runCatching {
        log("◆", "starting temporary local backend: $bin", appLog)
        startBackendProcess(console = false)
    }.getOrElse {
        stopLocalDb()
        throw it
    }
    try {
        localSmoke()
    } finally {
        stopBackendProcess(process)
        stopLocalDb()
    }
}

fun allTests() {
    localTests()
    publicSmoke()
    arcanaSmoke()
}

fun appendDeployHistory(
    mode: String,
    durationMs: Long,
    status: String = "OK",
    detail: String = "verifyServer, dashboard build-if-needed, installServer, local smoke",
) {
    val head = q("git rev-parse --short HEAD 2>/dev/null || true").ifBlank { "unknown" }
    deployHistory.appendText(
        listOf(
            "\"repo\":\"backend\"",
            "\"label\":${"deploy $head".jsonString()}",
            "\"status\":${status.jsonString()}",
            "\"timestamp_ms\":${System.currentTimeMillis()}",
            "\"duration_ms\":$durationMs",
            "\"head\":${head.jsonString()}",
            "\"mode\":${mode.jsonString()}",
            "\"detail\":${detail.jsonString()}",
        ).joinToString(prefix = "{", postfix = "}\n"),
    )
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

fun startLocalDb() {
    if (!localDbCompose.exists()) fail("Missing local Postgres compose file: ${localDbCompose.absolutePath}")
    log("◆", "starting local postgres")
    localDb("up -d postgres")
    log("◆", "waiting for local postgres on 127.0.0.1:$postgresPort")
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
    while (System.nanoTime() < deadline) {
        if (localDb("exec -T postgres pg_isready -U x -d x", check = false, quiet = true).code == 0) {
            return log("✓", "local postgres is ready")
        }
        Thread.sleep(1_000)
    }
    fail("local postgres did not become ready within 45s")
}

fun stopLocalDb() {
    if (!localDbCompose.exists()) return
    log("◆", "stopping local postgres")
    localDb("down --volumes --remove-orphans", check = false)
}

fun startBackendProcess(console: Boolean): Process {
    if (!File(bin).exists()) fail("Missing runtime script: $bin. Run deploy first.")
    return ProcessBuilder(bin)
        .directory(root)
        .redirectErrorStream(true)
        .also {
            it.environment().putIfAbsent("JAVA_HOME", javaHome)
            it.environment().putIfAbsent("LOG_DIR", logs.absolutePath)
            it.environment()["BACKEND_ENV"] = "local"
            it.environment()["PATH"] = "$javaHome/bin:${System.getenv("PATH").orEmpty()}"
            if (console) it.inheritIO() else it.redirectOutput(ProcessBuilder.Redirect.appendTo(appLog))
        }
        .start()
}

fun stopBackendProcess(process: Process) {
    if (!process.isAlive) return
    log("◆", "stopping $app process pid=${process.pid()}", appLog)
    process.destroy()
    if (!process.waitFor(5, TimeUnit.SECONDS)) {
        log("⚠", "process did not stop; killing pid=${process.pid()}", appLog)
        process.destroyForcibly()
        process.waitFor(5, TimeUnit.SECONDS)
    }
}

fun foreground(start: Long, mode: String): Nothing {
    startLocalDb()
    log("◆", "starting $app in foreground: $bin", appLog)
    val process = runCatching { startBackendProcess(console = true) }
        .getOrElse {
            stopLocalDb()
            throw it
        }
    val stopped = AtomicBoolean(false)
    fun stopForeground() {
        if (!stopped.compareAndSet(false, true)) return
        stopBackendProcess(process)
        stopLocalDb()
    }
    val stopHook = Thread(::stopForeground)
    Runtime.getRuntime().addShutdownHook(stopHook)
    localSmoke()
    runCatching {
        appendDeployHistory(
            mode = mode,
            durationMs = (System.nanoTime() - start) / 1_000_000,
            detail = "Local health probes passed.",
        )
    }.onFailure { log("⚠", "could not write local preview history: ${it.message}") }
    val exit = process.waitFor()
    runCatching { Runtime.getRuntime().removeShutdownHook(stopHook) }
    stopForeground()
    exitProcess(exit)
}

fun systemctl(action: String) = run("sudo systemctl --no-ask-password $action $service")

fun startService() {
    systemctl("reset-failed")
    systemctl("start")
}

fun gradle(tasks: String) = run("./gradlew $tasks --warning-mode all", buildLog)

fun scriptChanged(from: String, to: String): Boolean =
    from.isNotBlank() && to.isNotBlank() && from != to &&
        run("git diff --name-only ${from.shellQuote()} ${to.shellQuote()} -- $scriptPath", null, check = false, quiet = true)
            .out
            .lineSequence()
            .any { it == scriptPath }

fun changedIn(from: String, to: String, paths: List<String>): Boolean =
    from.isNotBlank() && to.isNotBlank() && from != to &&
        run("git diff --name-only ${from.shellQuote()} ${to.shellQuote()} -- ${paths.shellPathspec()}", null, check = false, quiet = true)
            .out
            .lineSequence()
            .any(String::isNotBlank)

fun dirtyIn(paths: List<String>): Boolean =
    run("git status --porcelain -- ${paths.shellPathspec()}", null, check = false, quiet = true)
        .out
        .lineSequence()
        .any(String::isNotBlank)

fun dashboardWebBuildReason(from: String, to: String): String? = when {
    !dashboardWebArtifact.isFile -> "missing artifact"
    changedIn(from, to, dashboardWebInputs) -> "dashboard inputs changed"
    dirtyIn(dashboardWebInputs) -> "dirty dashboard inputs"
    else -> null
}

fun buildDashboardWebIfNeeded(from: String, to: String) {
    val reason = dashboardWebBuildReason(from, to) ?: return log("✓", "dashboard web artifact is current")
    log("◆", "building dashboard web artifact: $reason")
    gradle(":dashboard:web:wasmJsBrowserDistribution")
}

fun kotlinRunner(): String =
    sequenceOf(
        q("command -v kotlin || true"),
        System.getenv("KOTLIN_HOME")?.let { "$it/bin/kotlin" }.orEmpty(),
        File(System.getProperty("user.home"), ".sdkman/candidates/kotlin/current/bin/kotlin").path,
    )
        .filter(String::isNotBlank)
        .firstOrNull { File(it).canExecute() }
        ?: fail("Could not find executable kotlin runner for deploy reload.")

fun reloadDeploy(command: String, syncFrom: String): Nothing {
    log("◆", "$scriptPath changed during git sync; restarting updated deploy script")
    val process = ProcessBuilder(kotlinRunner(), scriptFile.absolutePath, command)
        .directory(root)
        .inheritIO()
        .also { it.environment()[syncFromEnv] = syncFrom }
        .start()
    val exit = process.waitFor()
    exitProcess(exit)
}

fun syncGit() = run(
    """
    set -euo pipefail
    orig_head=${'$'}(git rev-parse HEAD)
    dirty=0
    autostashes() {
      echo "Recoverable backend autostashes:"
      git stash list --grep="autostash:$app" || true
    }
    restore_head() {
      git rebase --abort >/dev/null 2>&1 || true
      git reset --hard "${'$'}orig_head"
    }
    restore() {
      restore_head
      if [ "${'$'}dirty" -eq 1 ] && ! git stash pop --index; then
        echo "Git sync: restore stash pop failed; local changes remain recoverable."
        autostashes
        return 1
      fi
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
    if [ "${'$'}dirty" -eq 1 ] && ! git stash pop --index; then
      echo "Git sync: stash pop failed; local changes remain recoverable."
      autostashes
      restore_head || true
      exit 1
    fi
    """.trimIndent(),
    buildLog,
)

fun <T> withLock(block: () -> T): T =
    RandomAccessFile(lockFile, "rw").channel.use { channel ->
        val lock = channel.tryLock() ?: fail("Another deploy is already running: ${lockFile.absolutePath}")
        lock.use { block() }
    }

fun installAndForeground(start: Long, mode: String): Nothing {
    log("◆", "using foreground process mode")
    stopPort()
    gradle(":installServer")
    foreground(start, mode)
}

fun deploy() {
    val start = System.nanoTime()
    val mode = serviceMode()
    val reloadFrom = runCatching {
        withLock<String?> {
            val carriedSyncFrom = System.getenv(syncFromEnv)?.takeIf(String::isNotBlank)
            val beforeSync = carriedSyncFrom ?: head()
            log("◆", "deploying $app")
            listOf(
                "mode" to mode,
                "head" to q("git rev-parse --short HEAD 2>/dev/null || true"),
            ).forEach { (name, value) -> log(" ", "${name.padEnd(12)} $value") }
            syncGit()
            if (carriedSyncFrom == null && scriptChanged(beforeSync, head())) return@withLock beforeSync
            gradle(":verifyServer")
            buildDashboardWebIfNeeded(beforeSync, head())

            when (mode) {
                "runtime" -> {
                    log("✓", "verification passed; runtime systemd unit detected")
                    systemctl("stop")
                    gradle(":installServer")
                    startService()
                    localSmoke()
                }
                "legacy" -> {
                    if (!insideBackendService()) {
                        fail("$service still self-deploys through deploy.main.kts. Install the runtime unit before detached deploy.")
                    }
                    installAndForeground(start, mode)
                }
                "other" -> fail("$service has an unsupported ExecStart: ${execStart()}")
                else -> installAndForeground(start, mode)
            }

            val durationMs = (System.nanoTime() - start) / 1_000_000
            appendDeployHistory(mode, durationMs)
            log("✓", "deploy complete in ${durationMs}ms")
            null
        }
    }.getOrElse {
        val durationMs = (System.nanoTime() - start) / 1_000_000
        runCatching { appendDeployHistory(mode, durationMs, "FAIL", it.message ?: it::class.qualifiedName.orEmpty()) }
            .onFailure { historyError -> log("⚠", "could not write deploy failure history: ${historyError.message}") }
        throw it
    }
    if (reloadFrom != null) reloadDeploy("deploy", reloadFrom)
}

fun status() {
    log("◆", "$app status")
    listOf(
        "project" to root.toString(),
        "head" to q("git rev-parse --short HEAD 2>/dev/null || true").ifBlank { "unknown" },
        "mode" to serviceMode(),
        "exec" to (execStart() ?: "unavailable"),
        "port:$port" to (portPid()?.let { "listening pid=$it" } ?: if (portOpen()) "listening" else "closed"),
    ).forEach { (name, value) -> log(" ", "${name.padEnd(12)} $value") }
}

fun dispatch(command: String) {
    val mode = serviceMode()
    when (command) {
        "deploy", "" -> deploy()
        "start" -> when (mode) {
            "runtime" -> { startService(); localSmoke() }
            "other" -> fail("$service has an unsupported ExecStart: ${execStart()}")
            else -> foreground(System.nanoTime(), mode)
        }
        "stop" -> when (mode) {
            "runtime" -> systemctl("stop")
            "other" -> fail("$service has an unsupported ExecStart: ${execStart()}")
            else -> { stopPort(); stopLocalDb() }
        }
        "status" -> status()
        "smoke", "local-smoke" -> localSmoke()
        "public-smoke" -> publicSmoke()
        "arcana-smoke" -> arcanaSmoke()
        "local-tests" -> localTests()
        "all-tests" -> allTests()
        "verify" -> gradle(":verifyServer :installServer")
        else -> fail("Usage: ./0_scripts/deploy.main.kts [deploy|start|stop|status|verify|local-smoke|public-smoke|arcana-smoke|local-tests|all-tests]")
    }
}

val scriptExit = runCatching { dispatch(args.firstOrNull() ?: "deploy") }
    .fold(
        onSuccess = { 0 },
        onFailure = {
            if (it !is ScriptFailure) log("✗", it.message ?: it::class.qualifiedName.orEmpty())
            1
        },
    )
if (scriptExit != 0) exitProcess(scriptExit)
