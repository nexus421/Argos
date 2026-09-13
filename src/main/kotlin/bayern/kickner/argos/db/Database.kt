package bayern.kickner.argos.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.sqlite.SQLiteConfig
import java.io.File

/**
 * Dedicated single-threaded dispatcher ensuring all SQLite writes are serialized.
 */
val writeDispatcher = Dispatchers.IO.limitedParallelism(1)

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
 * Connects to the SQLite database with HikariCP, enables WAL mode, and creates tables.
 *
 * `busy_timeout` and `journal_mode` are passed as driver properties instead of `connectionInitSql`:
 * sqlite-jdbc's `Statement.execute()` only runs the first statement of a multi-statement string, and
 * the driver applies the busy timeout when opening the connection, i.e. before the WAL pragma.
 *
 * @param dataDir Storage directory path for the SQLite database.
 * @return Initialized [AppDatabase].
 */
fun connectDatabase(dataDir: String): AppDatabase {
    val dir = File(dataDir)
    dir.mkdirs()
    val dbFile = File(dir, "argos.db")

    val sqliteConfig = SQLiteConfig().apply {
        setBusyTimeout(5000)
        setJournalMode(SQLiteConfig.JournalMode.WAL)
    }

    val hikariConfig = HikariConfig().apply {
        jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"
        driverClassName = "org.sqlite.JDBC"
        maximumPoolSize = 4
        dataSourceProperties = sqliteConfig.toProperties()
    }

    val dataSource = HikariDataSource(hikariConfig)
    val database = Database.connect(dataSource)

    transaction(database) {
        SchemaUtils.create(CheckHistoryTable, SelfMonitorTable)
    }

    return AppDatabase(database, dataSource)
}

/**
 * Executes a write transaction sequentially via the single-threaded [writeDispatcher].
 */
suspend fun <T> dbWrite(database: Database, block: () -> T): T = withContext(writeDispatcher) {
    transaction(database) { block() }
}

/**
 * Executes a read transaction asynchronously on [Dispatchers.IO].
 */
suspend fun <T> dbRead(database: Database, block: () -> T): T = withContext(Dispatchers.IO) {
    transaction(database) { block() }
}
