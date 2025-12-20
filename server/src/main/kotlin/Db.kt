package net.sdfgsdfg

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.hours

private const val DEFAULT_DB_URL = "jdbc:postgresql://localhost:5432/x"
private const val DEFAULT_DB_USER = "x"
private const val DEFAULT_DB_PASSWORD = "x"
private const val RETENTION_MONTHS = 24

val RequestEventStartKey = AttributeKey<Long>("request-event-start-ns")
val RequestEventRecordedKey = AttributeKey<Boolean>("request-event-recorded")

object RequestEvents {
    private val logger = LoggerFactory.getLogger("db.RequestEvents")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val monthFormatter = DateTimeFormatter.ofPattern("yyyyMM")
    private var dataSource: HikariDataSource? = null
    private var initialized = false
    private var maintenanceStarted = false

    fun init() {
        if (initialized) return
        runCatching {
            val env = System.getenv()
            val jdbcUrl = env["DB_URL"] ?: DEFAULT_DB_URL
            val user = env["DB_USER"] ?: DEFAULT_DB_USER
            val pass = env["DB_PASSWORD"] ?: DEFAULT_DB_PASSWORD
            val config = HikariConfig().apply {
                this.jdbcUrl = jdbcUrl
                username = user
                password = pass
                maximumPoolSize = env["DB_POOL_SIZE"]?.toIntOrNull() ?: 5
                minimumIdle = env["DB_POOL_MIN_IDLE"]?.toIntOrNull() ?: 1
                connectionTimeout = env["DB_POOL_TIMEOUT_MS"]?.toLongOrNull() ?: 5_000
                poolName = "backend-db"
            }
            val ds = HikariDataSource(config)
            Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load()
                .migrate()
            Database.connect(ds)
            dataSource = ds
            initialized = true
            logger.info("DB ready: {}", jdbcUrl)
            startMaintenanceLoop()
        }.onFailure { err ->
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
        if (!initialized) return
        scope.launch {
            runCatching {
                transaction {
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

    private fun startMaintenanceLoop() {
        if (maintenanceStarted) return
        maintenanceStarted = true
        scope.launch {
            while (isActive) {
                runMaintenance()
                delay(12.hours)
            }
        }
    }

    private fun runMaintenance() {
        runCatching { ensurePartitions() }
            .onFailure { err -> logger.warn("Partition ensure failed.", err) }
        runCatching { dropOldPartitions() }
            .onFailure { err -> logger.warn("Partition cleanup failed.", err) }
    }

    private fun ensurePartitions() {
        val now = YearMonth.now()
        val next = now.plusMonths(1)
        transaction {
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

    private fun dropOldPartitions() {
        val cutoff = YearMonth.now().minusMonths(RETENTION_MONTHS.toLong())
        val partitions = transaction {
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
            transaction {
                toDrop.forEach { name ->
                    exec("DROP TABLE IF EXISTS $name")
                }
            }
        }
        transaction {
            exec("DELETE FROM request_event_default WHERE ts < now() - interval '24 months'")
        }
    }
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
