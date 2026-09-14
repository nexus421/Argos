package bayern.kickner.argos.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.exists
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.sqlite.SQLiteConfig
import java.io.File

/** One writer connection plus this many readers; must match [POOL_SIZE] so no coroutine ever waits for a connection. */
private const val READ_PARALLELISM = 3
private const val POOL_SIZE = READ_PARALLELISM + 1

/**
 * Schema version this build expects. Bump it and add a step to [migrations] whenever a table or column changes.
 * Version 1: check_history + self_monitor. Version 2: + pending_alert, schema_version.
 */
const val SCHEMA_VERSION = 2

/**
 * Migration steps keyed by the version they upgrade FROM. New tables need no step (SchemaUtils.create adds them);
 * altered tables do, e.g. `2 to { exec("ALTER TABLE check_history ADD COLUMN ...") }`.
 */
private val migrations: Map<Int, JdbcTransaction.() -> Unit> = mapOf(
    1 to fun JdbcTransaction.() { /* 1 -> 2: only new tables, created by SchemaUtils.create */ }
)

/**
 * Dedicated single-threaded dispatcher ensuring all SQLite writes are serialized.
 */
val writeDispatcher = Dispatchers.IO.limitedParallelism(1)

/**
 * Bounded dispatcher for reads. Views of Dispatchers.IO are not limited by its 64-thread cap, so reads keep
 * their threads even while every check thread is stuck in a blocking OS call (see checks/BlockingTimeout.kt).
 */
val readDispatcher = Dispatchers.IO.limitedParallelism(READ_PARALLELISM)

/**
 * Open SQLite database plus the pool that owns its connections, so shutdown can release both.
 *
 * @property database Exposed database handle used by [dbRead]/[dbWrite].
 */
class AppDatabase(val database: Database, private val dataSource: HikariDataSource) : AutoCloseable {
    override fun close() {
        TransactionManager.closeAndUnregister(database)
        dataSource.close()
    }
}

/**
 * Connects to the SQLite database with HikariCP, enables WAL mode, creates missing tables and applies schema
 * migrations. Throws when the directory or file cannot be used; the caller decides how to degrade.
 *
 * `busy_timeout` and `journal_mode` are passed as driver properties instead of `connectionInitSql`:
 * sqlite-jdbc's `Statement.execute()` only runs the first statement of a multi-statement string, and
 * the driver applies the busy timeout when opening the connection, i.e. before the WAL pragma.
 * `synchronous=NORMAL` skips the fsync per commit; with WAL that risks losing the last commits on power loss,
 * never corruption — acceptable for check history.
 *
 * @param dataDir Storage directory path for the SQLite database.
 * @return Initialized [AppDatabase].
 */
fun connectDatabase(dataDir: String): AppDatabase {
    val dir = File(dataDir)
    dir.mkdirs()
    val usable = dir.isDirectory && dir.canWrite()
    if (usable.not()) throw IllegalStateException("'${dir.absolutePath}' is not a writable directory")
    val dbFile = File(dir, "argos.db")

    val sqliteConfig = SQLiteConfig().apply {
        setBusyTimeout(5000)
        setJournalMode(SQLiteConfig.JournalMode.WAL)
        setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
    }

    val hikariConfig = HikariConfig().apply {
        jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"
        driverClassName = "org.sqlite.JDBC"
        maximumPoolSize = POOL_SIZE
        dataSourceProperties = sqliteConfig.toProperties()
    }

    val dataSource = HikariDataSource(hikariConfig)
    val database = Database.connect(dataSource)

    runCatching { transaction(database) { migrateSchema() } }
        .onFailure { dataSource.close() }
        .getOrThrow()

    return AppDatabase(database, dataSource)
}

/**
 * Creates missing tables and walks [migrations] from the stored version to [SCHEMA_VERSION]. A database without
 * a `schema_version` row is either brand new (no tables yet -> current version) or from a build before
 * versioning existed (-> version 1). A database from a NEWER build is refused: a downgraded binary must not
 * touch data it does not understand.
 */
private fun JdbcTransaction.migrateSchema() {
    val fresh = CheckHistoryTable.exists().not()
    SchemaUtils.create(SchemaVersionTable, CheckHistoryTable, SelfMonitorTable, PendingAlertTable)

    val stored = SchemaVersionTable.selectAll().firstOrNull()?.get(SchemaVersionTable.version)
    val from = stored ?: if (fresh) SCHEMA_VERSION else 1
    require(from <= SCHEMA_VERSION) { "database schema version $from is newer than this build supports ($SCHEMA_VERSION)" }

    for (step in from until SCHEMA_VERSION) {
        val migrate = migrations[step] ?: throw IllegalStateException("no migration from schema version $step")
        migrate(this)
    }

    SchemaVersionTable.deleteAll()
    SchemaVersionTable.insert { it[version] = SCHEMA_VERSION }
}

/**
 * Executes a write transaction sequentially via the single-threaded [writeDispatcher].
 */
suspend fun <T> dbWrite(database: Database, block: () -> T): T = withContext(writeDispatcher) {
    transaction(database) { block() }
}

/**
 * Executes a read transaction on the bounded [readDispatcher].
 */
suspend fun <T> dbRead(database: Database, block: () -> T): T = withContext(readDispatcher) {
    transaction(database) { block() }
}
