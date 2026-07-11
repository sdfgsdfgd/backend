package net.sdfgsdfg

import com.zaxxer.hikari.HikariDataSource
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.hours

private const val DEFAULT_DB_URL = "jdbc:postgresql://localhost:5432/x"
private const val DEFAULT_DB_USER = "x"
private const val DEFAULT_DB_PASSWORD = "x"
private const val RETENTION_MONTHS = 3

val RequestEventStartKey = AttributeKey<Long>("request-event-start-ns")
val RequestEventRecordedKey = AttributeKey<Boolean>("request-event-recorded")

internal enum class IpDisposition { DEFAULT, ALLOW, BLOCK }

/** Runtime-owned request telemetry: one database pool, one IO scope, one shutdown seam. */
internal class RequestEvents : AutoCloseable {
    private val logger = LoggerFactory.getLogger("db.RequestEvents")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val monthFormatter = DateTimeFormatter.ofPattern("yyyyMM")
    private val metricWindows = listOf("5 minutes" to "5m", "1 hour" to "1h")
    private val closed = AtomicBoolean()
    @Volatile private var database: Database? = null
    private var dataSource: HikariDataSource? = null

    fun init() {
        if (database != null || closed.get()) return
        var opened: HikariDataSource? = null
        runCatching {
            val env = System.getenv()
            val jdbcUrl = env["DB_URL"] ?: DEFAULT_DB_URL
            val user = env["DB_USER"] ?: DEFAULT_DB_USER
            val pass = env["DB_PASSWORD"] ?: DEFAULT_DB_PASSWORD
            val ds = HikariDataSource().apply {
                this.jdbcUrl = jdbcUrl
                username = user
                password = pass
                maximumPoolSize = env["DB_POOL_SIZE"]?.toIntOrNull() ?: 5
                minimumIdle = env["DB_POOL_MIN_IDLE"]?.toIntOrNull() ?: 1
                connectionTimeout = env["DB_POOL_TIMEOUT_MS"]?.toLongOrNull() ?: 5_000
                poolName = "backend-db"
            }.also { opened = it }
            Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load()
                .migrate()
            dataSource = ds
            database = Database.connect(ds)
            logger.info("DB ready: {}", jdbcUrl)
            startMaintenanceLoop()
        }.onFailure { err ->
            database = null
            dataSource = null
            opened?.close()
            logger.error("DB init failed; monitoring disabled.", err)
        }
    }

    fun record(
        ip: String,
        host: String?,
        method: String?,
        path: String?,
        rawQuery: String?,
        status: Int?,
        latencyMs: Int?,
        ua: String?,
        matchedRule: String?,
        requestId: String?,
        suspiciousReason: String?,
        severity: Int?
    ) {
        val database = database ?: return
        scope.launch {
            runCatching {
                database.tx {
                    RequestEventTable.insert {
                        it[ts] = OffsetDateTime.now(ZoneOffset.UTC)
                        it[this.ip] = ip
                        it[this.host] = host
                        it[this.method] = method
                        it[this.path] = path
                        it[this.rawQuery] = rawQuery
                        it[this.status] = status
                        it[this.latencyMs] = latencyMs
                        it[this.ua] = ua
                        it[this.matchedRule] = matchedRule
                        it[this.requestId] = requestId
                        it[this.suspiciousReason] = suspiciousReason
                        it[this.severity] = severity
                    }
                }
            }.onFailure { err ->
                logger.warn("Failed to record request event.", err)
            }
        }
    }

    suspend fun disposition(ip: String): IpDisposition {
        val database = database ?: return IpDisposition.DEFAULT
        return withContext(Dispatchers.IO) {
            runCatching {
                // Preserve allowlist precedence in one round trip; no cache keeps admin changes immediate.
                database.tx {
                    exec(
                        """
                        SELECT CASE
                            WHEN EXISTS (SELECT 1 FROM ip_allowlist WHERE ip = ?) THEN 'ALLOW'
                            WHEN EXISTS (SELECT 1 FROM ip_blacklist WHERE ip = ?) THEN 'BLOCK'
                            ELSE 'DEFAULT'
                        END
                        """.trimIndent(),
                        listOf(
                            IpAllowlistTable.ip.columnType to ip,
                            IpBlacklistTable.ip.columnType to ip,
                        ),
                        StatementType.SELECT,
                    ) { result ->
                        result.takeIf { it.next() }
                            ?.getString(1)
                            ?.let(IpDisposition::valueOf)
                            ?: IpDisposition.DEFAULT
                    } ?: IpDisposition.DEFAULT
                }
            }.getOrDefault(IpDisposition.DEFAULT)
        }
    }

    suspend fun blacklist(
        ip: String,
        reason: String?,
        countryCode: String?,
        async: Boolean = true
    ) {
        val database = database?.takeIf { ip.isNotBlank() } ?: return
        val write = {
            runCatching { database.upsertBlacklist(ip, reason, countryCode) }
                .onFailure { err -> logger.warn("Failed to update blacklist.", err) }
        }
        if (async) {
            scope.launch { write() }
        } else {
            withContext(Dispatchers.IO) { write() }
        }
    }

    suspend fun allowlist(ip: String, note: String?) {
        val database = database?.takeIf { ip.isNotBlank() } ?: return
        withContext(Dispatchers.IO) {
            runCatching { database.upsertAllowlist(ip, note) }
                .onFailure { err -> logger.warn("Failed to update allowlist.", err) }
        }
    }

    suspend fun prometheusMetrics(): String {
        val out = StringBuilder()
        out.appendLine("# HELP backend_request_event_class_total Rolling request-event count grouped by signal class")
        out.appendLine("# TYPE backend_request_event_class_total gauge")
        out.appendLine("# HELP backend_request_event_class_by_host_total Rolling request-event count grouped by host and signal class")
        out.appendLine("# TYPE backend_request_event_class_by_host_total gauge")
        out.appendLine("# HELP backend_request_event_5xx_total Rolling upstream/server failure count grouped by host, route, and status")
        out.appendLine("# TYPE backend_request_event_5xx_total gauge")
        val database = database ?: return out.toString()

        return withContext(Dispatchers.IO) {
            metricWindows.forEach { (interval, window) ->
                database.queryClassCounts(interval).forEach { (klass, count) ->
                    out.append("backend_request_event_class_total{window=\"")
                        .append(label(window))
                        .append("\",class=\"")
                        .append(label(klass))
                        .append("\"} ")
                        .appendLine(count)
                }
                database.queryHostClassCounts(interval).forEach { row ->
                    out.append("backend_request_event_class_by_host_total{window=\"")
                        .append(label(window))
                        .append("\",host=\"")
                        .append(label(row.host))
                        .append("\",class=\"")
                        .append(label(row.klass))
                        .append("\"} ")
                        .appendLine(row.count)
                }
                database.query5xxCounts(interval).forEach { row ->
                    out.append("backend_request_event_5xx_total{window=\"")
                        .append(label(window))
                        .append("\",host=\"")
                        .append(label(row.host))
                        .append("\",matched_rule=\"")
                        .append(label(row.matchedRule))
                        .append("\",status=\"")
                        .append(label(row.status))
                        .append("\"} ")
                        .appendLine(row.count)
                }
            }
            out.toString()
        }
    }

    private fun startMaintenanceLoop() {
        scope.launch {
            while (isActive) {
                database?.runMaintenance()
                delay(12.hours)
            }
        }
    }

    private fun Database.runMaintenance() {
        runCatching { ensurePartitions() }
            .onFailure { err -> logger.warn("Partition ensure failed.", err) }
        runCatching { dropOldPartitions() }
            .onFailure { err -> logger.warn("Partition cleanup failed.", err) }
    }

    private fun Database.ensurePartitions() {
        val now = YearMonth.now()
        val next = now.plusMonths(1)
        tx {
            createPartitionIfMissing(this, now)
            createPartitionIfMissing(this, next)
        }
    }

    private fun createPartitionIfMissing(tx: Transaction, month: YearMonth) {
        val name = "request_event_${month.format(monthFormatter)}"
        val start = month.atDay(1)
        val end = month.plusMonths(1).atDay(1)
        tx.exec(
            "CREATE TABLE IF NOT EXISTS $name " +
                "PARTITION OF request_event FOR VALUES FROM ('$start') TO ('$end');"
        )
    }

    private fun Database.dropOldPartitions() {
        val cutoff = YearMonth.now().minusMonths(RETENTION_MONTHS.toLong())
        val partitions = tx {
            exec(
                "SELECT tablename FROM pg_tables " +
                    "WHERE schemaname = 'public' AND tablename LIKE 'request_event_%'"
            ) { rs ->
                val names = mutableListOf<String>()
                while (rs.next()) {
                    names.add(rs.getString(1))
                }
                names
            }
        }.orEmpty()

        val toDrop = partitions.filter { name ->
            val suffix = name.removePrefix("request_event_")
            if (suffix == "default" || suffix.length != 6) return@filter false
            val ym = runCatching { YearMonth.parse(suffix, monthFormatter) }.getOrNull()
            ym != null && ym.isBefore(cutoff)
        }

        if (toDrop.isNotEmpty()) {
            tx {
                toDrop.forEach { name ->
                    exec("DROP TABLE IF EXISTS $name")
                }
            }
        }
        tx {
            exec("DELETE FROM request_event_default WHERE ts < now() - interval '${RETENTION_MONTHS} months'")
        }
    }

    private fun Database.upsertBlacklist(ip: String, reason: String?, countryCode: String?) {
        tx {
            val sql = """
                INSERT INTO ip_blacklist (ip, reason, country_code, first_seen, last_seen, hits)
                VALUES (?, ?, ?, now(), now(), 1)
                ON CONFLICT (ip) DO UPDATE SET
                    last_seen = now(),
                    hits = ip_blacklist.hits + 1,
                    reason = COALESCE(EXCLUDED.reason, ip_blacklist.reason),
                    country_code = COALESCE(ip_blacklist.country_code, EXCLUDED.country_code);
            """.trimIndent()
            val args = listOf(
                IpBlacklistTable.ip.columnType to ip,
                IpBlacklistTable.reason.columnType to reason,
                IpBlacklistTable.countryCode.columnType to countryCode
            )
            exec(sql, args, StatementType.OTHER)
        }
    }

    private fun Database.upsertAllowlist(ip: String, note: String?) {
        tx {
            val insertSql = """
                INSERT INTO ip_allowlist (ip, note, created_at)
                VALUES (?, ?, now())
                ON CONFLICT (ip) DO UPDATE SET
                    note = COALESCE(EXCLUDED.note, ip_allowlist.note);
            """.trimIndent()
            val args = listOf(
                IpAllowlistTable.ip.columnType to ip,
                IpAllowlistTable.note.columnType to note
            )
            exec(insertSql, args, StatementType.OTHER)
            IpBlacklistTable.deleteWhere { IpBlacklistTable.ip eq ip }
        }
    }

    private data class HostClassCount(val host: String, val klass: String, val count: Long)
    private data class FailureCount(val host: String, val matchedRule: String, val status: String, val count: Long)

    private fun Database.queryClassCounts(interval: String): List<Pair<String, Long>> = tx {
        val rows = mutableListOf<Pair<String, Long>>()
        exec(
            """
            SELECT class, count(*)
            FROM (
                SELECT COALESCE(
                    suspicious_reason,
                    CASE
                        WHEN status >= 500 THEN 'UPSTREAM_5XX'
                        WHEN status >= 400 THEN 'HTTP_4XX'
                        ELSE 'OK_OR_INFO'
                    END
                ) AS class
                FROM request_event
                WHERE ts > now() - interval '$interval'
            ) s
            GROUP BY class
            ORDER BY count(*) DESC;
            """.trimIndent()
        ) { rs ->
            while (rs.next()) {
                rows.add(rs.getString(1) to rs.getLong(2))
            }
        }
        rows
    }

    private fun Database.queryHostClassCounts(interval: String): List<HostClassCount> = tx {
        val rows = mutableListOf<HostClassCount>()
        exec(
            """
            SELECT COALESCE(host, ''), class, count(*)
            FROM (
                SELECT host, COALESCE(
                    suspicious_reason,
                    CASE
                        WHEN status >= 500 THEN 'UPSTREAM_5XX'
                        WHEN status >= 400 THEN 'HTTP_4XX'
                        ELSE 'OK_OR_INFO'
                    END
                ) AS class
                FROM request_event
                WHERE ts > now() - interval '$interval'
            ) s
            WHERE class <> 'OK_OR_INFO'
            GROUP BY host, class
            ORDER BY count(*) DESC
            LIMIT 30;
            """.trimIndent()
        ) { rs ->
            while (rs.next()) {
                rows.add(HostClassCount(rs.getString(1), rs.getString(2), rs.getLong(3)))
            }
        }
        rows
    }

    private fun Database.query5xxCounts(interval: String): List<FailureCount> = tx {
        val rows = mutableListOf<FailureCount>()
        exec(
            """
            SELECT COALESCE(host, ''), COALESCE(matched_rule, ''), status::text, count(*)
            FROM request_event
            WHERE ts > now() - interval '$interval'
              AND status >= 500
              AND suspicious_reason IS NULL
            GROUP BY host, matched_rule, status
            ORDER BY count(*) DESC
            LIMIT 30;
            """.trimIndent()
        ) { rs ->
            while (rs.next()) {
                rows.add(FailureCount(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)))
            }
        }
        rows
    }

    private fun label(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        database = null
        scope.cancel()
        dataSource?.close()
        dataSource = null
    }

    private fun <T> Database.tx(statement: Transaction.() -> T): T =
        transaction(this, statement = statement)
}

object RequestEventTable : Table("request_event") {
    val id = long("id").autoIncrement()
    val ts = timestampWithTimeZone("ts")
    val ip = text("ip")
    val host = text("host").nullable()
    val method = text("method").nullable()
    val path = text("path").nullable()
    val rawQuery = text("raw_query").nullable()
    val status = integer("status").nullable()
    val latencyMs = integer("latency_ms").nullable()
    val ua = text("ua").nullable()
    val matchedRule = text("matched_rule").nullable()
    val requestId = text("request_id").nullable()
    val suspiciousReason = text("suspicious_reason").nullable()
    val severity = integer("severity").nullable()

    override val primaryKey = PrimaryKey(id, ts)
}

object IpBlacklistTable : Table("ip_blacklist") {
    val ip = text("ip")
    val reason = text("reason").nullable()
    val countryCode = text("country_code").nullable()
    val firstSeen = timestampWithTimeZone("first_seen")
    val lastSeen = timestampWithTimeZone("last_seen")
    val hits = long("hits")
    override val primaryKey = PrimaryKey(ip)
}

object IpAllowlistTable : Table("ip_allowlist") {
    val ip = text("ip")
    val note = text("note").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(ip)
}
