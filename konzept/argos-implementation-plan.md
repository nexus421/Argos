# Argos Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Kotlin/JVM-Monitoring-Tool ("Argos") als einzelne Fat Jar — HTTP/TCP/Ping/DNS-Checks, SMTP+Webhook-Notifications, Status-Pages mit optionalem Basic-Auth, Self-Monitoring per Heartbeat. Kein Docker, kein Login-System.

**Architecture:** Ktor-Server (CIO-Engine) mit einem Coroutines-Ticker als Scheduler, Exposed + SQLite (WAL) für Check-Historie, statische JSON-Config für Monitor-Definitionen, SSR via kotlinx.html für Status-Pages, ein rein clientseitiger Config-Generator als Static Resource.

**Tech Stack:** Kotlin 2.4.10, JDK 25 (Amazon Corretto), Ktor 3.5.2, Exposed 1.5.0 + org.xerial:sqlite-jdbc 3.53.4.0, HikariCP 6.3.0, kotlinx.serialization-json 1.9.0, com.sun.mail:jakarta.mail 2.0.2, bayern.kickner:Klogger 0.1.0, bayern.kickner:KotNexLib 4.3.0, Kotest 5.9.1, Mockk 1.13.13.

**Spec:** Ergebnis der Konzept-Diskussion in diesem Chat (Kuvasz-Alternative, KISS, ohne Login/Docker).

## Global Constraints

- group = `bayern.kickner`, Package-Root `bayern.kickner.argos`, Artefakt-Name `Argos`
- Kein Docker, kein Login/Session/2FA, keine Mutations-API
- JSON-Config ist statisch, kein Hot-Reload — Config-Änderung erfordert Neustart
- Nur Kotlin Coroutines — kein RxJava, keine virtuellen Threads
- SQLite: `busy_timeout` vor `journal_mode=WAL`, Writes ausschließlich über `Dispatchers.IO.limitedParallelism(1)`
- Code-Style: `.not()` statt `!`, `runCatching` als Standard, sealed classes statt nullable Felder, `if`/`when` als Ausdruck statt `var`-Mutation (Konvention aus `bayern.kickner`-Projekten)
- Server muss auch bei fehlender/fehlerhafter Config starten (leerer Zustand + Hinweis), niemals crashen
- Ping/ICMP erfordert Root-Rechte für den Prozess (siehe Task 11)
- Klogger/KotNexLib-Importe sind aus deren README nicht 1:1 ersichtlich (nur Funktionsnamen ohne `import`-Zeilen) — beim ersten Build per IDE-Autovervollständigung verifizieren

---

## Task 1: Projekt-Grundgerüst

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `src/main/kotlin/bayern/kickner/argos/Main.kt`

**Interfaces:**
- Produces: `fun main()` — lauffähiger Einstiegspunkt

- [ ] **Step 1: `settings.gradle.kts` anlegen**

```kotlin
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "Argos"
```

- [ ] **Step 2: `build.gradle.kts` anlegen**

```kotlin
plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    id("io.ktor.plugin") version "3.5.2"
    application
}

group = "bayern.kickner"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven {
        name = "nexus421MavenReleases"
        url = uri("https://maven.kickner.bayern/releases")
    }
}

dependencies {
    implementation("bayern.kickner:Klogger:0.1.0")
    implementation("bayern.kickner:KotNexLib:4.3.0")

    implementation("io.ktor:ktor-server-core:3.5.2")
    implementation("io.ktor:ktor-server-cio:3.5.2")
    implementation("io.ktor:ktor-server-auth:3.5.2")
    implementation("io.ktor:ktor-server-html-builder:3.5.2")
    implementation("io.ktor:ktor-client-core:3.5.2")
    implementation("io.ktor:ktor-client-cio:3.5.2")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    implementation("org.jetbrains.exposed:exposed-core:1.5.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:1.5.0")
    implementation("org.jetbrains.exposed:exposed-java-time:1.5.0")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("com.zaxxer:HikariCP:6.3.0")

    implementation("com.sun.mail:jakarta.mail:2.0.2")

    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    testImplementation("io.mockk:mockk:1.13.13")
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.AMAZON)
    }
}

application {
    mainClass.set("bayern.kickner.argos.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

ktor {
    fatJar {
        archiveFileName.set("argos.jar")
    }
}
```

- [ ] **Step 3: `gradle.properties` anlegen**

```properties
kotlin.code.style=official
org.gradle.jvmargs=-Xmx2g
```

- [ ] **Step 4: Gradle-Wrapper generieren**

Run: `gradle wrapper --gradle-version 9.6`

- [ ] **Step 5: `Main.kt` mit Platzhalter-Ausgabe anlegen**

```kotlin
package bayern.kickner.argos

fun main() {
    println("Argos boots.")
}
```

- [ ] **Step 6: Build verifizieren**

Run: `./gradlew run`
Expected: Ausgabe `Argos boots.`

- [ ] **Step 7: Commit**

```bash
git init
git add .
git commit -m "chore: Projekt-Grundgerüst"
```

---

## Task 2: Config-Modelle + Laden/Validieren

**Files:**
- Create: `src/main/kotlin/bayern/kickner/argos/config/CheckConfig.kt`
- Create: `src/main/kotlin/bayern/kickner/argos/config/AppConfig.kt`
- Create: `src/main/kotlin/bayern/kickner/argos/config/ConfigLoader.kt`
- Test: `src/test/kotlin/bayern/kickner/argos/config/ConfigLoaderTest.kt`

**Interfaces:**
- Produces: `AppConfig`, `MonitorConfig`, `CheckConfig` (sealed: `HttpCheckConfig`, `TcpCheckConfig`, `PingCheckConfig`, `DnsCheckConfig`), `SmtpConfig`, `WebhookConfig`, `StatusPageConfig`, `BasicAuthConfig`, `ConfigError`, `fun loadConfig(path: String): ResultOf2<AppConfig, ConfigError>`

- [ ] **Step 1: `CheckConfig.kt` anlegen**

```kotlin
package bayern.kickner.argos.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface CheckConfig

@Serializable
@SerialName("http")
data class HttpCheckConfig(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val expectedStatusCodes: List<Int> = listOf(200),
    val bodyRegex: String? = null,
    val followRedirects: Boolean = true
) : CheckConfig

@Serializable
@SerialName("tcp")
data class TcpCheckConfig(
    val host: String,
    val port: Int
) : CheckConfig

@Serializable
@SerialName("ping")
data class PingCheckConfig(
    val host: String
) : CheckConfig

@Serializable
@SerialName("dns")
data class DnsCheckConfig(
    val hostname: String,
    val expectedIp: String? = null
) : CheckConfig
```

- [ ] **Step 2: `AppConfig.kt` anlegen**

```kotlin
package bayern.kickner.argos.config

import kotlinx.serialization.Serializable

@Serializable
data class MonitorConfig(
    val id: String,
    val name: String,
    val intervalSeconds: Long,
    val timeoutSeconds: Long,
    val check: CheckConfig,
    val notificationChannelIds: List<String>? = null
)

@Serializable
data class SmtpConfig(
    val id: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val from: String,
    val to: List<String>,
    val systemEvents: Boolean = true
)

@Serializable
data class WebhookConfig(
    val id: String,
    val url: String,
    val method: String = "POST",
    val headers: Map<String, String> = emptyMap(),
    val bodyTemplate: String,
    val systemEvents: Boolean = true
)

@Serializable
data class BasicAuthConfig(
    val username: String,
    val passwordHash: String
)

@Serializable
data class StatusPageConfig(
    val id: String,
    val name: String,
    val monitorIds: List<String>,
    val basicAuth: BasicAuthConfig? = null
)

@Serializable
data class AppConfig(
    val monitors: List<MonitorConfig> = emptyList(),
    val smtpChannels: List<SmtpConfig> = emptyList(),
    val webhookChannels: List<WebhookConfig> = emptyList(),
    val statusPages: List<StatusPageConfig> = emptyList(),
    val retentionDays: Int = 365,
    val flappingThreshold: Int = 3,
    val heartbeatGapMinutesThreshold: Int = 2,
    val dataDir: String = "./data"
)
```

- [ ] **Step 3: Test `ConfigLoaderTest.kt` schreiben**

```kotlin
package bayern.kickner.argos.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotnexlib.ResultOf2
import java.io.File

class ConfigLoaderTest : FunSpec({

    test("lädt eine gültige Config mit HTTP- und TCP-Check") {
        val tmp = File.createTempFile("argos-config", ".json")
        tmp.writeText(
            """
            {
              "monitors": [
                { "id": "m1", "name": "API", "intervalSeconds": 60, "timeoutSeconds": 10,
                  "check": { "type": "http", "url": "https://example.com" } },
                { "id": "m2", "name": "DB", "intervalSeconds": 30, "timeoutSeconds": 5,
                  "check": { "type": "tcp", "host": "localhost", "port": 5432 } }
              ]
            }
            """.trimIndent()
        )

        val result = loadConfig(tmp.absolutePath)

        result.shouldBeInstanceOf<ResultOf2.Success<AppConfig, ConfigError>>()
        (result as ResultOf2.Success).value.monitors.size shouldBe 2
        tmp.delete()
    }

    test("meldet doppelte Monitor-IDs als Validierungsfehler") {
        val tmp = File.createTempFile("argos-config", ".json")
        tmp.writeText(
            """
            {
              "monitors": [
                { "id": "dup", "name": "A", "intervalSeconds": 60, "timeoutSeconds": 10,
                  "check": { "type": "tcp", "host": "a", "port": 1 } },
                { "id": "dup", "name": "B", "intervalSeconds": 60, "timeoutSeconds": 10,
                  "check": { "type": "tcp", "host": "b", "port": 2 } }
              ]
            }
            """.trimIndent()
        )

        loadConfig(tmp.absolutePath).shouldBeInstanceOf<ResultOf2.Failure<AppConfig, ConfigError>>()
        tmp.delete()
    }

    test("lehnt timeoutSeconds >= intervalSeconds ab") {
        val tmp = File.createTempFile("argos-config", ".json")
        tmp.writeText(
            """
            {
              "monitors": [
                { "id": "m1", "name": "A", "intervalSeconds": 10, "timeoutSeconds": 10,
                  "check": { "type": "tcp", "host": "a", "port": 1 } }
              ]
            }
            """.trimIndent()
        )

        loadConfig(tmp.absolutePath).shouldBeInstanceOf<ResultOf2.Failure<AppConfig, ConfigError>>()
        tmp.delete()
    }

    test("meldet fehlende Datei ohne Exception") {
        loadConfig("/tmp/does-not-exist-argos-config.json")
            .shouldBeInstanceOf<ResultOf2.Failure<AppConfig, ConfigError>>()
    }
})
```

- [ ] **Step 4: Test laufen lassen, Fehlschlag erwarten**

Run: `./gradlew test --tests "bayern.kickner.argos.config.ConfigLoaderTest"`
Expected: FAIL (Unresolved reference: `loadConfig`)

- [ ] **Step 5: `ConfigLoader.kt` implementieren**

```kotlin
package bayern.kickner.argos.config

import kotlinx.serialization.json.Json
import kotnexlib.ResultOf2
import java.io.File

sealed interface ConfigError {
    data class ParseError(val message: String) : ConfigError
    data class ValidationError(val issues: List<String>) : ConfigError
    data class FileNotFound(val path: String) : ConfigError
}

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    classDiscriminator = "type"
}

fun loadConfig(path: String): ResultOf2<AppConfig, ConfigError> {
    val file = File(path)
    if (file.exists().not()) return ResultOf2.Failure(ConfigError.FileNotFound(path))

    val parsed = runCatching { json.decodeFromString<AppConfig>(file.readText()) }
        .getOrElse { return ResultOf2.Failure(ConfigError.ParseError(it.message ?: "unknown")) }

    val issues = validate(parsed)
    if (issues.isNotEmpty()) return ResultOf2.Failure(ConfigError.ValidationError(issues))

    return ResultOf2.Success(parsed)
}

private fun validate(config: AppConfig): List<String> {
    val issues = mutableListOf<String>()

    val duplicateIds = config.monitors.groupBy { it.id }.filterValues { it.size > 1 }.keys
    if (duplicateIds.isNotEmpty()) issues += "Doppelte Monitor-IDs: $duplicateIds"

    config.monitors.forEach { monitor ->
        val tooSlow = monitor.timeoutSeconds >= monitor.intervalSeconds
        if (tooSlow) issues += "Monitor '${monitor.id}': timeoutSeconds muss kleiner als intervalSeconds sein"
    }

    return issues
}
```

- [ ] **Step 6: Test erneut laufen lassen**

Run: `./gradlew test --tests "bayern.kickner.argos.config.ConfigLoaderTest"`
Expected: PASS (4 Tests)

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/bayern/kickner/argos/config src/test/kotlin/bayern/kickner/argos/config
git commit -m "feat: Config-Modelle, Laden und Validierung"
```

---

## Task 3: Datenbank (Exposed + SQLite)

**Files:**
- Create: `src/main/kotlin/bayern/kickner/argos/db/Tables.kt`
- Create: `src/main/kotlin/bayern/kickner/argos/db/Database.kt`
- Test: `src/test/kotlin/bayern/kickner/argos/db/DatabaseTest.kt`

**Interfaces:**
- Consumes: nichts (Basis-Layer)
- Produces: `CheckHistoryTable`, `SelfMonitorTable`, `fun connectDatabase(dataDir: String): Database`, `suspend fun <T> dbWrite(database: Database, block: () -> T): T`, `suspend fun <T> dbRead(database: Database, block: () -> T): T`

- [ ] **Step 1: `Tables.kt` anlegen**

```kotlin
package bayern.kickner.argos.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object CheckHistoryTable : Table("check_history") {
    val id = long("id").autoIncrement()
    val monitorId = varchar("monitor_id", 64).index()
    val timestamp = timestamp("timestamp").index()
    val success = bool("success")
    val responseTimeMs = long("response_time_ms")
    val errorMessage = varchar("error_message", 1024).nullable()

    override val primaryKey = PrimaryKey(id)
}

object SelfMonitorTable : Table("self_monitor") {
    val id = integer("id")
    val lastHeartbeat = timestamp("last_heartbeat")

    override val primaryKey = PrimaryKey(id)
}
```

- [ ] **Step 2: Test `DatabaseTest.kt` schreiben**

```kotlin
package bayern.kickner.argos.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import java.nio.file.Files
import java.time.Instant

class DatabaseTest : FunSpec({

    test("schreibt und liest einen CheckHistory-Eintrag über den Write-Dispatcher") = runBlocking {
        val tempDir = Files.createTempDirectory("argos-db-test").toFile()
        val database = connectDatabase(tempDir.absolutePath)

        dbWrite(database) {
            CheckHistoryTable.insert {
                it[monitorId] = "m1"
                it[timestamp] = Instant.now()
                it[success] = true
                it[responseTimeMs] = 42L
                it[errorMessage] = null
            }
        }

        val rows = dbRead(database) { CheckHistoryTable.selectAll().toList() }
        rows.size shouldBe 1
        rows.first()[CheckHistoryTable.monitorId] shouldBe "m1"

        tempDir.deleteRecursively()
    }
})
```

- [ ] **Step 3: Test laufen lassen, Fehlschlag erwarten**

Run: `./gradlew test --tests "bayern.kickner.argos.db.DatabaseTest"`
Expected: FAIL (Unresolved reference: `connectDatabase`, `dbWrite`, `dbRead`)

- [ ] **Step 4: `Database.kt` implementieren**

```kotlin
package bayern.kickner.argos.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

val writeDispatcher = Dispatchers.IO.limitedParallelism(1)

fun connectDatabase(dataDir: String): Database {
    val dir = File(dataDir)
    dir.mkdirs()
    val dbFile = File(dir, "argos.db")

    val hikariConfig = HikariConfig().apply {
        jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"
        driverClassName = "org.sqlite.JDBC"
        maximumPoolSize = 4
        connectionInitSql = "PRAGMA busy_timeout = 5000; PRAGMA journal_mode = WAL;"
    }

    val database = Database.connect(HikariDataSource(hikariConfig))

    transaction(database) {
        SchemaUtils.create(CheckHistoryTable, SelfMonitorTable)
    }

    return database
}

suspend fun <T> dbWrite(database: Database, block: () -> T): T = withContext(writeDispatcher) {
    transaction(database) { block() }
}

suspend fun <T> dbRead(database: Database, block: () -> T): T = withContext(Dispatchers.IO) {
    transaction(database) { block() }
}
```

- [ ] **Step 5: Test erneut laufen lassen**

Run: `./gradlew test --tests "bayern.kickner.argos.db.DatabaseTest"`
Expected: PASS

- [ ] **Step 6: Manuell prüfen, dass WAL aktiv ist**

Run: `sqlite3 <tempDir>/argos.db "PRAGMA journal_mode;"` (mit einem Pfad aus einem manuellen Testlauf)
Expected: `wal`

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/bayern/kickner/argos/db src/test/kotlin/bayern/kickner/argos/db
git commit -m "feat: Exposed/SQLite-Anbindung mit seriellem Write-Dispatcher"
```

---

## Task 4: Check-Implementierungen

**Files:**
- Create: `src/main/kotlin/bayern/kickner/argos/checks/CheckResult.kt`
- Create: `src/main/kotlin/bayern/kickner/argos/checks/HttpCheck.kt`
- Create: `src/main/kotlin/bayern/kickner/argos/checks/TcpCheck.kt`
- Create: `src/main/kotlin/bayern/kickner/argos/checks/PingCheck.kt`
- Create: `src/main/kotlin/bayern/kickner/argos/checks/DnsCheck.kt`
- Create: `src/main/kotlin/bayern/kickner/argos/checks/CheckExecutor.kt`
- Test: `src/test/kotlin/bayern/kickner/argos/checks/TcpCheckTest.kt`
- Test: `src/test/kotlin/bayern/kickner/argos/checks/DnsCheckTest.kt`

**Interfaces:**
- Consumes: `CheckConfig`-Hierarchie aus Task 2
- Produces: `data class CheckResult(success: Boolean, responseTimeMs: Long, message: String?)`, `suspend fun executeCheck(check: CheckConfig, timeoutSeconds: Long, httpClient: HttpClient): CheckResult`

- [ ] **Step 1: `CheckResult.kt` anlegen**

```kotlin
package bayern.kickner.argos.checks

data class CheckResult(
    val success: Boolean,
    val responseTimeMs: Long,
    val message: String?
)
```

- [ ] **Step 2: Test `TcpCheckTest.kt` schreiben (gegen einen lokalen ServerSocket)**

```kotlin
package bayern.kickner.argos.checks

import bayern.kickner.argos.config.TcpCheckConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket

class TcpCheckTest : FunSpec({

    test("meldet Erfolg für einen offenen Port") = runBlocking {
        val serverSocket = ServerSocket(0)
        val result = executeTcpCheck(TcpCheckConfig("localhost", serverSocket.localPort), timeoutSeconds = 2)
        result.success shouldBe true
        serverSocket.close()
    }

    test("meldet Misserfolg für einen geschlossenen Port") = runBlocking {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort
        serverSocket.close()

        val result = executeTcpCheck(TcpCheckConfig("localhost", port), timeoutSeconds = 2)
        result.success shouldBe false
    }
})
```

- [ ] **Step 3: Test laufen lassen, Fehlschlag erwarten**

Run: `./gradlew test --tests "bayern.kickner.argos.checks.TcpCheckTest"`
Expected: FAIL (Unresolved reference: `executeTcpCheck`)

- [ ] **Step 4: `TcpCheck.kt` implementieren**

```kotlin
package bayern.kickner.argos.checks

import bayern.kickner.argos.config.TcpCheckConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

suspend fun executeTcpCheck(config: TcpCheckConfig, timeoutSeconds: Long): CheckResult = withContext(Dispatchers.IO) {
    val start = System.currentTimeMillis()
    runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(config.host, config.port), (timeoutSeconds * 1000).toInt())
        }
    }.fold(
        onSuccess = { CheckResult(true, System.currentTimeMillis() - start, null) },
        onFailure = { CheckResult(false, System.currentTimeMillis() - start, it.message) }
    )
}
```

- [ ] **Step 5: Test erneut laufen lassen**

Run: `./gradlew test --tests "bayern.kickner.argos.checks.TcpCheckTest"`
Expected: PASS

- [ ] **Step 6: `PingCheck.kt` implementieren (kein automatisierter Test — ICMP braucht Root, siehe Task 11)**

```kotlin
package bayern.kickner.argos.checks

import bayern.kickner.argos.config.PingCheckConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

suspend fun executePingCheck(config: PingCheckConfig, timeoutSeconds: Long): CheckResult = withContext(Dispatchers.IO) {
    val start = System.currentTimeMillis()
    runCatching {
        InetAddress.getByName(config.host).isReachable((timeoutSeconds * 1000).toInt())
    }.fold(
        onSuccess = { reachable ->
            CheckResult(
                success = reachable,
                responseTimeMs = System.currentTimeMillis() - start,
                message = if (reachable.not()) "Host nicht erreichbar (ICMP oder TCP-Fallback)" else null
            )
        },
        onFailure = { CheckResult(false, System.currentTimeMillis() - start, it.message) }
    )
}
```

- [ ] **Step 7: Test `DnsCheckTest.kt` schreiben**

```kotlin
package bayern.kickner.argos.checks

import bayern.kickner.argos.config.DnsCheckConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking

class DnsCheckTest : FunSpec({

    test("löst localhost erfolgreich auf") = runBlocking {
        val result = executeDnsCheck(DnsCheckConfig("localhost"), timeoutSeconds = 2)
        result.success shouldBe true
    }

    test("meldet Misserfolg bei falscher erwarteter IP") = runBlocking {
        val result = executeDnsCheck(DnsCheckConfig("localhost", expectedIp = "203.0.113.99"), timeoutSeconds = 2)
        result.success shouldBe false
    }
})
```

- [ ] **Step 8: Test laufen lassen, Fehlschlag erwarten**

Run: `./gradlew test --tests "bayern.kickner.argos.checks.DnsCheckTest"`
Expected: FAIL (Unresolved reference: `executeDnsCheck`)

- [ ] **Step 9: `DnsCheck.kt` implementieren**

```kotlin
package bayern.kickner.argos.checks

import bayern.kickner.argos.config.DnsCheckConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

suspend fun executeDnsCheck(config: DnsCheckConfig, timeoutSeconds: Long): CheckResult = withContext(Dispatchers.IO) {
    val start = System.currentTimeMillis()
    runCatching {
        InetAddress.getAllByName(config.hostname)
    }.fold(
        onSuccess = { addresses ->
            val ips = addresses.map { it.hostAddress }
            val matches = config.expectedIp == null || ips.contains(config.expectedIp)
            CheckResult(
                success = matches,
                responseTimeMs = System.currentTimeMillis() - start,
                message = if (matches.not()) "Aufgelöste IPs $ips enthalten nicht ${config.expectedIp}" else null
            )
        },
        onFailure = { CheckResult(false, System.currentTimeMillis() - start, it.message) }
    )
}
```

- [ ] **Step 10: Test erneut laufen lassen**

Run: `./gradlew test --tests "bayern.kickner.argos.checks.DnsCheckTest"`
Expected: PASS

- [ ] **Step 11: `HttpCheck.kt` implementieren**

```kotlin
package bayern.kickner.argos.checks

import bayern.kickner.argos.config.HttpCheckConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import kotlinx.coroutines.withTimeout

private val clientWithRedirects = HttpClient(CIO) { followRedirects = true }
private val clientWithoutRedirects = HttpClient(CIO) { followRedirects = false }

suspend fun executeHttpCheck(config: HttpCheckConfig, timeoutSeconds: Long): CheckResult {
    val start = System.currentTimeMillis()
    val client = if (config.followRedirects) clientWithRedirects else clientWithoutRedirects

    return runCatching {
        withTimeout(timeoutSeconds * 1000) {
            val response = client.request(config.url) {
                method = HttpMethod.parse(config.method)
                config.headers.forEach { (key, value) -> header(key, value) }
            }
            val statusOk = config.expectedStatusCodes.contains(response.status.value)
            val bodyOk = config.bodyRegex?.let { Regex(it).containsMatchIn(response.bodyAsText()) } ?: true
            statusOk && bodyOk
        }
    }.fold(
        onSuccess = { ok ->
            CheckResult(
                success = ok,
                responseTimeMs = System.currentTimeMillis() - start,
                message = if (ok.not()) "Status oder Body-Regex nicht erfüllt" else null
            )
        },
        onFailure = { CheckResult(false, System.currentTimeMillis() - start, it.message) }
    )
}
```

- [ ] **Step 12: `CheckExecutor.kt` implementieren (Dispatcher-Funktion)**

```kotlin
package bayern.kickner.argos.checks

import bayern.kickner.argos.config.CheckConfig
import bayern.kickner.argos.config.DnsCheckConfig
import bayern.kickner.argos.config.HttpCheckConfig
import bayern.kickner.argos.config.PingCheckConfig
import bayern.kickner.argos.config.TcpCheckConfig

suspend fun executeCheck(check: CheckConfig, timeoutSeconds: Long): CheckResult = when (check) {
    is HttpCheckConfig -> executeHttpCheck(check, timeoutSeconds)
    is TcpCheckConfig -> executeTcpCheck(check, timeoutSeconds)
    is PingCheckConfig -> executePingCheck(check, timeoutSeconds)
    is DnsCheckConfig -> executeDnsCheck(check, timeoutSeconds)
}
```

- [ ] **Step 13: Commit**

```bash
git add src/main/kotlin/bayern/kickner/argos/checks src/test/kotlin/bayern/kickner/argos/checks
git commit -m "feat: HTTP/TCP/Ping/DNS-Checks"
```

---

## Task 5: Trigger-Logik (Flapping-Schutz + Recovery)

**Files:**
- Create: `src/main/kotlin/bayern/kickner/argos/notify/TriggerLogic.kt`
- Test: `src/test/kotlin/bayern/kickner/argos/notify/TriggerLogicTest.kt`

**Interfaces:**
- Produces: `data class MonitorRuntimeState(consecutiveFailures: Int, currentlyDown: Boolean)`, `sealed interface TriggerDecision` (`None`, `SendDownNotification`, `SendRecoveryNotification`), `fun evaluateTrigger(previous: MonitorRuntimeState, checkSucceeded: Boolean, flappingThreshold: Int): Pair<MonitorRuntimeState, TriggerDecision>`

- [ ] **Step 1: Test `TriggerLogicTest.kt` schreiben**

```kotlin
package bayern.kickner.argos.notify

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TriggerLogicTest : FunSpec({

    test("löst erst nach flappingThreshold aufeinanderfolgenden Fehlern DOWN aus") {
        var state = MonitorRuntimeState()
        repeat(2) {
            val (next, decision) = evaluateTrigger(state, checkSucceeded = false, flappingThreshold = 3)
            decision shouldBe TriggerDecision.None
            state = next
        }
        val (finalState, decision) = evaluateTrigger(state, checkSucceeded = false, flappingThreshold = 3)
        decision shouldBe TriggerDecision.SendDownNotification
        finalState.currentlyDown shouldBe true
    }

    test("löst sofort Recovery aus, sobald ein Check nach DOWN erfolgreich ist") {
        val downState = MonitorRuntimeState(consecutiveFailures = 3, currentlyDown = true)
        val (next, decision) = evaluateTrigger(downState, checkSucceeded = true, flappingThreshold = 3)
        decision shouldBe TriggerDecision.SendRecoveryNotification
        next.currentlyDown shouldBe false
    }

    test("meldet nichts bei einem einzelnen Fehlversuch unterhalb der Schwelle") {
        val (_, decision) = evaluateTrigger(MonitorRuntimeState(), checkSucceeded = false, flappingThreshold = 3)
        decision shouldBe TriggerDecision.None
    }

    test("meldet DOWN kein zweites Mal, solange der Monitor down bleibt") {
        val downState = MonitorRuntimeState(consecutiveFailures = 5, currentlyDown = true)
        val (_, decision) = evaluateTrigger(downState, checkSucceeded = false, flappingThreshold = 3)
        decision shouldBe TriggerDecision.None
    }
})
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag erwarten**

Run: `./gradlew test --tests "bayern.kickner.argos.notify.TriggerLogicTest"`
Expected: FAIL (Unresolved reference: `evaluateTrigger`)

- [ ] **Step 3: `TriggerLogic.kt` implementieren**

```kotlin
package bayern.kickner.argos.notify

data class MonitorRuntimeState(
    val consecutiveFailures: Int = 0,
    val currentlyDown: Boolean = false
)

sealed interface TriggerDecision {
    data object None : TriggerDecision
    data object SendDownNotification : TriggerDecision
    data object SendRecoveryNotification : TriggerDecision
}

fun evaluateTrigger(
    previous: MonitorRuntimeState,
    checkSucceeded: Boolean,
    flappingThreshold: Int
): Pair<MonitorRuntimeState, TriggerDecision> {
    if (checkSucceeded) {
        val recovered = previous.currentlyDown
        val newState = MonitorRuntimeState(consecutiveFailures = 0, currentlyDown = false)
        val decision = if (recovered) TriggerDecision.SendRecoveryNotification else TriggerDecision.None
        return newState to decision
    }

    val newFailureCount = previous.consecutiveFailures + 1
    val crossesThreshold = newFailureCount >= flappingThreshold && previous.currentlyDown.not()
    val newState = MonitorRuntimeState(
        consecutiveFailures = newFailureCount,
        currentlyDown = previous.currentlyDown || crossesThreshold
    )
    val decision = if (crossesThreshold) TriggerDecision.SendDownNotification else TriggerDecision.None
    return newState to decision
}
```

- [ ] **Step 4: Test erneut laufen lassen**

Run: `./gradlew test --tests "bayern.kickner.argos.notify.TriggerLogicTest"`
Expected: PASS (4 Tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/bayern/kickner/argos/notify/TriggerLogic.kt src/test/kotlin/bayern/kickner/argos/notify/TriggerLogicTest.kt
git commit -m "feat: Flapping-Schutz und Recovery-Trigger als reine Zustandsmaschine"
```

---

## Task 6: Notification-Versand (SMTP + Webhook)

**Files:**
- Create: `src/main/kotlin/bayern/kickner/argos/notify/Template.kt`
- Create: `src/main/kotlin/bayern/kickner/argos/notify/MailSender.kt`
- Create: `src/main/kotlin/bayern/kickner/argos/notify/WebhookSender.kt`
- Create: `src/main/kotlin/bayern/kickner/argos/notify/NotificationDispatcher.kt`
- Test: `src/test/kotlin/bayern/kickner/argos/notify/TemplateTest.kt`

**Interfaces:**
- Consumes: `SmtpConfig`, `WebhookConfig` aus Task 2, `TriggerDecision` aus Task 5
- Produces: `fun renderTemplate(template: String, placeholders: Map<String, String>): String`, `fun sendMail(config: SmtpConfig, subject: String, body: String)`, `suspend fun sendWebhook(config: WebhookConfig, httpClient: HttpClient, placeholders: Map<String, String>)`, `class NotificationDispatcher(config: AppConfig, httpClient: HttpClient)` mit `suspend fun sendSystemNotification(subject: String, body: String)` und `suspend fun sendMonitorNotification(monitorId: String, monitorName: String, subject: String, body: String, channelIds: List<String>?)`

- [ ] **Step 1: Test `TemplateTest.kt` schreiben**

```kotlin
package bayern.kickner.argos.notify

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TemplateTest : FunSpec({

    test("ersetzt alle Platzhalter im Template") {
        val template = """{"text": "{{monitorName}} ist {{status}}"}"""
        val result = renderTemplate(template, mapOf("monitorName" to "API", "status" to "DOWN"))
        result shouldBe """{"text": "API ist DOWN"}"""
    }

    test("lässt unbekannte Platzhalter unverändert stehen") {
        val result = renderTemplate("{{unknown}}", emptyMap())
        result shouldBe "{{unknown}}"
    }
})
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag erwarten**

Run: `./gradlew test --tests "bayern.kickner.argos.notify.TemplateTest"`
Expected: FAIL (Unresolved reference: `renderTemplate`)

- [ ] **Step 3: `Template.kt` implementieren**

```kotlin
package bayern.kickner.argos.notify

fun renderTemplate(template: String, placeholders: Map<String, String>): String =
    placeholders.entries.fold(template) { acc, (key, value) -> acc.replace("{{$key}}", value) }
```

- [ ] **Step 4: Test erneut laufen lassen**

Run: `./gradlew test --tests "bayern.kickner.argos.notify.TemplateTest"`
Expected: PASS

- [ ] **Step 5: `MailSender.kt` implementieren**

```kotlin
package bayern.kickner.argos.notify

import bayern.kickner.argos.config.SmtpConfig
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.util.Properties

fun sendMail(config: SmtpConfig, subject: String, body: String) {
    val props = Properties().apply {
        put("mail.smtp.host", config.host)
        put("mail.smtp.port", config.port.toString())
        put("mail.smtp.auth", "true")
        put("mail.smtp.starttls.enable", "true")
    }

    val session = Session.getInstance(props, object : Authenticator() {
        override fun getPasswordAuthentication() = PasswordAuthentication(config.username, config.password)
    })

    val message = MimeMessage(session).apply {
        setFrom(InternetAddress(config.from))
        setRecipients(Message.RecipientType.TO, config.to.map { InternetAddress(it) }.toTypedArray())
        setSubject(subject)
        setText(body)
    }

    Transport.send(message)
}
```

- [ ] **Step 6: `WebhookSender.kt` implementieren**

```kotlin
package bayern.kickner.argos.notify

import bayern.kickner.argos.config.WebhookConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.HttpMethod

suspend fun sendWebhook(config: WebhookConfig, httpClient: HttpClient, placeholders: Map<String, String>) {
    val body = renderTemplate(config.bodyTemplate, placeholders)

    httpClient.request(config.url) {
        method = HttpMethod.parse(config.method)
        config.headers.forEach { (key, value) -> header(key, value) }
        setBody(body)
    }
}
```

- [ ] **Step 7: `NotificationDispatcher.kt` implementieren**

```kotlin
package bayern.kickner.argos.notify

import bayern.kickner.argos.config.AppConfig
import io.ktor.client.HttpClient
import klogger.LogLevel
import klogger.staticLog

class NotificationDispatcher(
    private val config: AppConfig,
    private val httpClient: HttpClient
) {
    suspend fun sendSystemNotification(subject: String, body: String) {
        config.smtpChannels.filter { it.systemEvents }.forEach { smtp ->
            runCatching { sendMail(smtp, subject, body) }
                .onFailure { staticLog(LogLevel.ERROR, "NotificationDispatcher") { "SMTP-Systemnachricht fehlgeschlagen: ${it.message}" } }
        }
        config.webhookChannels.filter { it.systemEvents }.forEach { webhook ->
            runCatching { sendWebhook(webhook, httpClient, mapOf("subject" to subject, "body" to body)) }
                .onFailure { staticLog(LogLevel.ERROR, "NotificationDispatcher") { "Webhook-Systemnachricht fehlgeschlagen: ${it.message}" } }
        }
    }

    suspend fun sendMonitorNotification(
        monitorId: String,
        monitorName: String,
        subject: String,
        body: String,
        channelIds: List<String>?
    ) {
        val smtpTargets = config.smtpChannels.filter { channelIds == null || it.id in channelIds }
        val webhookTargets = config.webhookChannels.filter { channelIds == null || it.id in channelIds }

        smtpTargets.forEach { smtp ->
            runCatching { sendMail(smtp, subject, body) }
                .onFailure { staticLog(LogLevel.ERROR, "NotificationDispatcher") { "SMTP für $monitorName fehlgeschlagen: ${it.message}" } }
        }
        webhookTargets.forEach { webhook ->
            runCatching {
                sendWebhook(webhook, httpClient, mapOf("monitorId" to monitorId, "monitorName" to monitorName, "subject" to subject, "body" to body))
            }.onFailure { staticLog(LogLevel.ERROR, "NotificationDispatcher") { "Webhook für $monitorName fehlgeschlagen: ${it.message}" } }
        }
    }
}
```

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/bayern/kickner/argos/notify src/test/kotlin/bayern/kickner/argos/notify
git commit -m "feat: SMTP- und Webhook-Versand mit Template-Rendering"
```

---

## Task 7: Self-Monitoring (Heartbeat + Gap-Detection)

**Files:**
- Create: `src/main/kotlin/bayern/kickner/argos/selfmonitor/Heartbeat.kt`
- Create: `src/main/kotlin/bayern/kickner/argos/selfmonitor/GapDetection.kt`
- Test: `src/test/kotlin/bayern/kickner/argos/selfmonitor/GapDetectionTest.kt`

**Interfaces:**
- Consumes: `SelfMonitorTable`, `dbWrite`, `dbRead` aus Task 3
- Produces: `suspend fun writeHeartbeat(database: Database, now: Instant)`, `suspend fun readLastHeartbeat(database: Database): Instant?`, `fun hasUnplannedGap(lastHeartbeat: Instant?, now: Instant, thresholdMinutes: Int): Boolean`

- [ ] **Step 1: Test `GapDetectionTest.kt` schreiben**

```kotlin
package bayern.kickner.argos.selfmonitor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class GapDetectionTest : FunSpec({

    test("meldet keine Lücke beim allerersten Start (kein vorheriger Heartbeat)") {
        hasUnplannedGap(lastHeartbeat = null, now = Instant.now(), thresholdMinutes = 2) shouldBe false
    }

    test("meldet keine Lücke innerhalb der Schwelle") {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val last = now.minusSeconds(60)
        hasUnplannedGap(last, now, thresholdMinutes = 2) shouldBe false
    }

    test("meldet eine Lücke, sobald die Schwelle überschritten ist") {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val last = now.minusSeconds(180)
        hasUnplannedGap(last, now, thresholdMinutes = 2) shouldBe true
    }
})
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag erwarten**

Run: `./gradlew test --tests "bayern.kickner.argos.selfmonitor.GapDetectionTest"`
Expected: FAIL (Unresolved reference: `hasUnplannedGap`)

- [ ] **Step 3: `GapDetection.kt` implementieren**

```kotlin
package bayern.kickner.argos.selfmonitor

import java.time.Duration
import java.time.Instant

fun hasUnplannedGap(lastHeartbeat: Instant?, now: Instant, thresholdMinutes: Int): Boolean {
    if (lastHeartbeat == null) return false
    return Duration.between(lastHeartbeat, now).toMinutes() >= thresholdMinutes
}
```

- [ ] **Step 4: Test erneut laufen lassen**

Run: `./gradlew test --tests "bayern.kickner.argos.selfmonitor.GapDetectionTest"`
Expected: PASS (3 Tests)

- [ ] **Step 5: `Heartbeat.kt` implementieren**

```kotlin
package bayern.kickner.argos.selfmonitor

import bayern.kickner.argos.db.SelfMonitorTable
import bayern.kickner.argos.db.dbRead
import bayern.kickner.argos.db.dbWrite
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import java.time.Instant

suspend fun writeHeartbeat(database: Database, now: Instant) = dbWrite(database) {
    SelfMonitorTable.deleteAll()
    SelfMonitorTable.insert {
        it[id] = 0
        it[lastHeartbeat] = now
    }
}

suspend fun readLastHeartbeat(database: Database): Instant? = dbRead(database) {
    SelfMonitorTable.selectAll().firstOrNull()?.get(SelfMonitorTable.lastHeartbeat)
}
```

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/bayern/kickner/argos/selfmonitor src/test/kotlin/bayern/kickner/argos/selfmonitor
git commit -m "feat: Heartbeat-Schreiben und Gap-Detection für Self-Monitoring"
```

---

## Task 8: Scheduler

**Files:**
- Create: `src/main/kotlin/bayern/kickner/argos/scheduler/Scheduler.kt`
- Test: `src/test/kotlin/bayern/kickner/argos/scheduler/DueMonitorsTest.kt`

**Interfaces:**
- Consumes: `executeCheck` (Task 4), `evaluateTrigger` (Task 5), `NotificationDispatcher` (Task 6), `writeHeartbeat` (Task 7), `dbWrite`/`CheckHistoryTable` (Task 3)
- Produces: `fun dueMonitors(now: Instant, monitors: List<MonitorConfig>): List<MonitorConfig>`, `class Scheduler(config, database, httpClient, scope, notificationDispatcher)` mit `fun start()`

- [ ] **Step 1: Test `DueMonitorsTest.kt` schreiben**

```kotlin
package bayern.kickner.argos.scheduler

import bayern.kickner.argos.config.MonitorConfig
import bayern.kickner.argos.config.TcpCheckConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class DueMonitorsTest : FunSpec({

    fun monitor(id: String, interval: Long) = MonitorConfig(
        id = id, name = id, intervalSeconds = interval, timeoutSeconds = 1,
        check = TcpCheckConfig("host", 80)
    )

    test("wählt nur Monitore, deren Intervall zur aktuellen Epoch-Sekunde passt") {
        val now = Instant.ofEpochSecond(120)
        val monitors = listOf(monitor("a", 60), monitor("b", 35), monitor("c", 40))

        dueMonitors(now, monitors).map { it.id } shouldBe listOf("a", "c")
    }

    test("wählt mehrere Monitore mit demselben Intervall gemeinsam aus") {
        val now = Instant.ofEpochSecond(60)
        val monitors = listOf(monitor("a", 60), monitor("b", 60), monitor("c", 45))

        dueMonitors(now, monitors).map { it.id } shouldBe listOf("a", "b")
    }
})
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag erwarten**

Run: `./gradlew test --tests "bayern.kickner.argos.scheduler.DueMonitorsTest"`
Expected: FAIL (Unresolved reference: `dueMonitors`)

- [ ] **Step 3: `Scheduler.kt` implementieren**

```kotlin
package bayern.kickner.argos.scheduler

import bayern.kickner.argos.checks.executeCheck
import bayern.kickner.argos.config.AppConfig
import bayern.kickner.argos.config.MonitorConfig
import bayern.kickner.argos.db.CheckHistoryTable
import bayern.kickner.argos.db.dbWrite
import bayern.kickner.argos.notify.MonitorRuntimeState
import bayern.kickner.argos.notify.NotificationDispatcher
import bayern.kickner.argos.notify.TriggerDecision
import bayern.kickner.argos.notify.evaluateTrigger
import bayern.kickner.argos.selfmonitor.writeHeartbeat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.less
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

fun dueMonitors(now: Instant, monitors: List<MonitorConfig>): List<MonitorConfig> =
    monitors.filter { now.epochSecond % it.intervalSeconds == 0L }

class Scheduler(
    private val config: AppConfig,
    private val database: Database,
    private val scope: CoroutineScope,
    private val notificationDispatcher: NotificationDispatcher
) {
    private val runtimeState = ConcurrentHashMap<String, MonitorRuntimeState>()
    private var tickCount = 0L

    fun start() {
        scope.launch {
            while (true) {
                val now = Instant.now()

                dueMonitors(now, config.monitors).forEach { monitor ->
                    scope.launch { runCheck(monitor) }
                }

                tickCount++
                if (tickCount % 30 == 0L) {
                    scope.launch { writeHeartbeat(database, now) }
                }
                if (now.epochSecond % 86400 == 0L) {
                    scope.launch { cleanupOldHistory() }
                }

                delay(1000)
            }
        }
    }

    private suspend fun runCheck(monitor: MonitorConfig) {
        val result = executeCheck(monitor.check, monitor.timeoutSeconds)

        dbWrite(database) {
            CheckHistoryTable.insert {
                it[monitorId] = monitor.id
                it[timestamp] = Instant.now()
                it[success] = result.success
                it[responseTimeMs] = result.responseTimeMs
                it[errorMessage] = result.message
            }
        }

        val previous = runtimeState.getOrDefault(monitor.id, MonitorRuntimeState())
        val (newState, decision) = evaluateTrigger(previous, result.success, config.flappingThreshold)
        runtimeState[monitor.id] = newState

        when (decision) {
            TriggerDecision.SendDownNotification -> notificationDispatcher.sendMonitorNotification(
                monitor.id, monitor.name, "${monitor.name} ist DOWN", result.message ?: "Kein Detail", monitor.notificationChannelIds
            )
            TriggerDecision.SendRecoveryNotification -> notificationDispatcher.sendMonitorNotification(
                monitor.id, monitor.name, "${monitor.name} ist wieder UP", "Erholt", monitor.notificationChannelIds
            )
            TriggerDecision.None -> Unit
        }
    }

    private suspend fun cleanupOldHistory() {
        val cutoff = Instant.now().minusSeconds(config.retentionDays * 86400L)
        dbWrite(database) {
            CheckHistoryTable.deleteWhere { CheckHistoryTable.timestamp less cutoff }
        }
    }
}
```

- [ ] **Step 4: Test erneut laufen lassen**

Run: `./gradlew test --tests "bayern.kickner.argos.scheduler.DueMonitorsTest"`
Expected: PASS (2 Tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/bayern/kickner/argos/scheduler src/test/kotlin/bayern/kickner/argos/scheduler
git commit -m "feat: Epoch-aligned Scheduler mit Heartbeat und Retention-Cleanup"
```

---

## Task 9: Ktor-Webserver (Basic Auth + Status-Pages)

**Files:**
- Create: `src/main/kotlin/bayern/kickner/argos/web/WebModule.kt`

**Interfaces:**
- Consumes: `AppConfig`, `StatusPageConfig` aus Task 2
- Produces: `fun Application.configureWeb(config: AppConfig?)`

- [ ] **Step 1: `WebModule.kt` implementieren**

```kotlin
package bayern.kickner.argos.web

import bayern.kickner.argos.config.AppConfig
import bayern.kickner.argos.config.StatusPageConfig
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.basic
import io.ktor.server.html.respondHtml
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.html.body
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.li
import kotlinx.html.title
import kotlinx.html.ul
import kotnexlib.crypto.Argon2Helper

fun Application.configureWeb(config: AppConfig?) {
    if (config != null) {
        installBasicAuthProviders(config)
    }

    routing {
        staticResources("/setup", "static")

        if (config == null) {
            get("/") {
                call.respondText("Keine gültige Config gefunden. Öffne /setup, um eine JSON-Config zu erstellen.")
            }
            return@routing
        }

        config.statusPages.forEach { page ->
            if (page.basicAuth != null) {
                authenticate("auth-${page.id}") {
                    get("/status/${page.id}") { call.respondStatusPage(page) }
                }
            } else {
                get("/status/${page.id}") { call.respondStatusPage(page) }
            }
        }
    }
}

private fun Application.installBasicAuthProviders(config: AppConfig) {
    install(Authentication) {
        config.statusPages.forEach { page ->
            val auth = page.basicAuth ?: return@forEach
            basic("auth-${page.id}") {
                realm = "Argos Status Page: ${page.name}"
                validate { credentials ->
                    val hashOk = Argon2Helper.verify(credentials.password.toCharArray(), auth.passwordHash).getOrDefault(false)
                    if (credentials.name == auth.username && hashOk) UserIdPrincipal(credentials.name) else null
                }
            }
        }
    }
}

private suspend fun ApplicationCall.respondStatusPage(page: StatusPageConfig) {
    respondHtml {
        head { title { +page.name } }
        body {
            h1 { +page.name }
            ul { page.monitorIds.forEach { li { +it } } }
        }
    }
}
```

- [ ] **Step 2: Manueller Test — Server mit leerer Config starten**

Run: (nach Task 12 verdrahtet) `./gradlew run` ohne `configPath`-Argument
Expected: `curl http://localhost:8080/` liefert den Hinweistext, kein Absturz

- [ ] **Step 3: Manueller Test — Status-Page mit Basic Auth**

Config mit einer `statusPages`-Eintrag inkl. `basicAuth` anlegen, Server starten, dann:
Run: `curl -u user:falschesPasswort http://localhost:8080/status/<id>`
Expected: `401 Unauthorized`

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/bayern/kickner/argos/web
git commit -m "feat: Ktor-Webmodul mit Status-Pages und optionalem Basic-Auth pro Page"
```

---

## Task 10: Config-Generator (statisches HTML/JS)

**Files:**
- Create: `src/main/resources/static/index.html`

**Interfaces:**
- Produces: statische Seite unter `/setup`, erzeugt clientseitig ein `AppConfig`-kompatibles JSON

- [ ] **Step 1: `index.html` anlegen**

```html
<!DOCTYPE html>
<html lang="de">
<head>
<meta charset="UTF-8">
<title>Argos Config-Generator</title>
<style>
  body { font-family: sans-serif; max-width: 800px; margin: 2rem auto; background: #1e1e1e; color: #ddd; }
  fieldset { margin-bottom: 1rem; border-color: #444; }
  input, select, textarea { width: 100%; margin: 0.25rem 0; background: #2a2a2a; color: #ddd; border: 1px solid #555; }
  button { margin-top: 0.5rem; }
  #output { width: 100%; height: 300px; }
</style>
</head>
<body>
<h1>Argos Config-Generator</h1>

<input type="file" id="upload" accept="application/json">

<div id="monitors"></div>
<button id="addMonitor">+ Monitor hinzufügen</button>

<fieldset>
  <legend>Globale Einstellungen</legend>
  <label>Retention (Tage): <input type="number" id="retentionDays" value="365"></label>
  <label>Flapping-Schwelle: <input type="number" id="flappingThreshold" value="3"></label>
  <label>Heartbeat-Gap-Schwelle (Minuten): <input type="number" id="heartbeatGapMinutesThreshold" value="2"></label>
</fieldset>

<button id="generate">JSON generieren</button>
<textarea id="output" readonly></textarea>
<button id="download">Als Datei herunterladen</button>

<script>
let monitorCount = 0;

function addMonitorForm(prefill) {
  const container = document.getElementById('monitors');
  const index = monitorCount++;
  const div = document.createElement('div');
  div.className = 'monitor';
  div.dataset.index = index;
  div.innerHTML = `
    <fieldset>
      <legend>Monitor</legend>
      <label>Name: <input class="name" value="${prefill?.name ?? ''}"></label>
      <label>Intervall (Sekunden): <input class="interval" type="number" value="${prefill?.intervalSeconds ?? 60}"></label>
      <label>Timeout (Sekunden): <input class="timeout" type="number" value="${prefill?.timeoutSeconds ?? 10}"></label>
      <label>Check-Typ:
        <select class="checkType">
          <option value="http">HTTP(S)</option>
          <option value="tcp">TCP</option>
          <option value="ping">Ping</option>
          <option value="dns">DNS</option>
        </select>
      </label>
      <div class="checkFields"></div>
      <button class="remove">Entfernen</button>
    </fieldset>
  `;
  container.appendChild(div);

  const select = div.querySelector('.checkType');
  const fieldsDiv = div.querySelector('.checkFields');

  function renderFields() {
    const type = select.value;
    if (type === 'http') {
      fieldsDiv.innerHTML = `
        <label>URL: <input class="url" value=""></label>
        <label>Methode: <input class="method" value="GET"></label>
        <label>Erwartete Status-Codes (kommasepariert): <input class="statusCodes" value="200"></label>
        <label>Body-Regex (optional): <input class="bodyRegex" value=""></label>
        <label>Redirects folgen: <input class="followRedirects" type="checkbox" checked></label>
      `;
    } else if (type === 'tcp') {
      fieldsDiv.innerHTML = `
        <label>Host: <input class="host" value=""></label>
        <label>Port: <input class="port" type="number" value=""></label>
      `;
    } else if (type === 'ping') {
      fieldsDiv.innerHTML = `<label>Host: <input class="host" value=""></label>`;
    } else if (type === 'dns') {
      fieldsDiv.innerHTML = `
        <label>Hostname: <input class="hostname" value=""></label>
        <label>Erwartete IP (optional): <input class="expectedIp" value=""></label>
      `;
    }
  }
  select.addEventListener('change', renderFields);
  renderFields();

  div.querySelector('.remove').addEventListener('click', () => div.remove());
}

function collectMonitor(div) {
  const type = div.querySelector('.checkType').value;
  let check;
  if (type === 'http') {
    check = {
      type: 'http',
      url: div.querySelector('.url').value,
      method: div.querySelector('.method').value,
      expectedStatusCodes: div.querySelector('.statusCodes').value.split(',').map(s => parseInt(s.trim())),
      bodyRegex: div.querySelector('.bodyRegex').value || null,
      followRedirects: div.querySelector('.followRedirects').checked
    };
  } else if (type === 'tcp') {
    check = { type: 'tcp', host: div.querySelector('.host').value, port: parseInt(div.querySelector('.port').value) };
  } else if (type === 'ping') {
    check = { type: 'ping', host: div.querySelector('.host').value };
  } else {
    check = {
      type: 'dns',
      hostname: div.querySelector('.hostname').value,
      expectedIp: div.querySelector('.expectedIp').value || null
    };
  }

  return {
    id: crypto.randomUUID(),
    name: div.querySelector('.name').value,
    intervalSeconds: parseInt(div.querySelector('.interval').value),
    timeoutSeconds: parseInt(div.querySelector('.timeout').value),
    check
  };
}

document.getElementById('addMonitor').addEventListener('click', () => addMonitorForm());

document.getElementById('generate').addEventListener('click', () => {
  const monitors = [...document.querySelectorAll('.monitor')].map(collectMonitor);
  const config = {
    monitors,
    smtpChannels: [],
    webhookChannels: [],
    statusPages: [],
    retentionDays: parseInt(document.getElementById('retentionDays').value),
    flappingThreshold: parseInt(document.getElementById('flappingThreshold').value),
    heartbeatGapMinutesThreshold: parseInt(document.getElementById('heartbeatGapMinutesThreshold').value)
  };
  document.getElementById('output').value = JSON.stringify(config, null, 2);
});

document.getElementById('download').addEventListener('click', () => {
  const blob = new Blob([document.getElementById('output').value], { type: 'application/json' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = 'config.json';
  a.click();
});

document.getElementById('upload').addEventListener('change', (event) => {
  const file = event.target.files[0];
  if (!file) return;
  const reader = new FileReader();
  reader.onload = () => {
    const config = JSON.parse(reader.result);
    document.getElementById('retentionDays').value = config.retentionDays ?? 365;
    document.getElementById('flappingThreshold').value = config.flappingThreshold ?? 3;
    document.getElementById('heartbeatGapMinutesThreshold').value = config.heartbeatGapMinutesThreshold ?? 2;
    document.getElementById('monitors').innerHTML = '';
    (config.monitors ?? []).forEach(m => addMonitorForm(m));
  };
  reader.readAsText(file);
});
</script>
</body>
</html>
```

- [ ] **Step 2: Manueller Test**

`./gradlew run`, dann `http://localhost:8080/setup/index.html` im Browser öffnen, einen Monitor anlegen, JSON generieren, herunterladen, Inhalt gegen die `AppConfig`-Struktur aus Task 2 prüfen.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/index.html
git commit -m "feat: clientseitiger Config-Generator als Static Resource"
```

---

## Task 11: Main.kt Wiring

**Files:**
- Modify: `src/main/kotlin/bayern/kickner/argos/Main.kt`

**Interfaces:**
- Consumes: alles aus Task 2–9

- [ ] **Step 1: `Main.kt` vollständig ersetzen**

```kotlin
package bayern.kickner.argos

import bayern.kickner.argos.config.loadConfig
import bayern.kickner.argos.db.connectDatabase
import bayern.kickner.argos.notify.NotificationDispatcher
import bayern.kickner.argos.scheduler.Scheduler
import bayern.kickner.argos.selfmonitor.hasUnplannedGap
import bayern.kickner.argos.selfmonitor.readLastHeartbeat
import bayern.kickner.argos.web.configureWeb
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import klogger.LogLevel
import klogger.staticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotnexlib.ArgsInterpreter
import kotnexlib.ResultOf2
import java.time.Instant

fun main(args: Array<String>) {
    java.security.Security.setProperty("networkaddress.cache.ttl", "0")
    java.security.Security.setProperty("networkaddress.cache.negative.ttl", "0")

    val parsedArgs = ArgsInterpreter(args)
    val configPath = parsedArgs.getValue("configPath") ?: "./config.json"

    val configResult = loadConfig(configPath)
    val appConfig = (configResult as? ResultOf2.Success)?.value

    if (appConfig == null) {
        staticLog(LogLevel.ERROR, "Main") { "Config konnte nicht geladen werden ($configResult) — Server startet mit leerem Zustand." }
    }

    val database = connectDatabase(appConfig?.dataDir ?: "./data")
    val httpClient = HttpClient(CIO)
    val notificationDispatcher = NotificationDispatcher(appConfig ?: bayern.kickner.argos.config.AppConfig(), httpClient)

    runBlocking {
        val lastHeartbeat = readLastHeartbeat(database)
        val now = Instant.now()
        val gap = hasUnplannedGap(lastHeartbeat, now, appConfig?.heartbeatGapMinutesThreshold ?: 2)

        if (gap) {
            staticLog(LogLevel.WARN, "Main") { "Unerwarteter Neustart erkannt. Letzter Heartbeat: $lastHeartbeat" }
            notificationDispatcher.sendSystemNotification(
                subject = "Argos: unerwartet offline",
                body = "Zuletzt aktiv: $lastHeartbeat, jetzt gestartet: $now"
            )
        }

        notificationDispatcher.sendSystemNotification(subject = "Argos gestartet", body = "Gestartet um $now")
    }

    if (appConfig != null) {
        val appScope = CoroutineScope(SupervisorJob())
        Scheduler(appConfig, database, appScope, notificationDispatcher).start()
    }

    embeddedServer(ServerCIO, port = 8080) {
        configureWeb(appConfig)
    }.start(wait = true)
}
```

- [ ] **Step 2: End-to-End-Test manuell**

Eine `config.json` mit einem TCP-Check gegen `localhost:22` (oder einen anderen offenen Port) anlegen.
Run: `./gradlew run --args="configPath=config.json"`
Expected: Log-Ausgabe zeigt "Argos gestartet"-Notification-Versuch (schlägt ohne echte SMTP/Webhook-Config in den Logs sichtbar fehl, das ist erwartet), Server erreichbar unter `http://localhost:8080/`

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/bayern/kickner/argos/Main.kt
git commit -m "feat: Main.kt verdrahtet Config, DB, Scheduler, Self-Monitoring und Webserver"
```

---

## Task 12: systemd-Unit + CLAUDE.md

**Files:**
- Create: `scripts/argos.service`
- Create: `CLAUDE.md`

- [ ] **Step 1: `scripts/argos.service` anlegen**

```ini
[Unit]
Description=Argos Monitoring
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/opt/argos
ExecStart=/usr/bin/java -jar /opt/argos/argos.jar configPath=/opt/argos/config.json
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

- [ ] **Step 2: Installationsschritte ausführen**

```bash
sudo mkdir -p /opt/argos
sudo cp build/libs/argos.jar /opt/argos/argos.jar
sudo cp config.json /opt/argos/config.json
sudo cp scripts/argos.service /etc/systemd/system/argos.service
sudo systemctl daemon-reload
sudo systemctl enable argos.service
sudo systemctl start argos.service
```

- [ ] **Step 3: `CLAUDE.md` anlegen**

```markdown
# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

Argos ist ein Kotlin/JVM-Monitoring-Tool (`bayern.kickner`), Fat Jar, ohne Login/Docker. Nutzt
Klogger (Logging) und KotNexLib (`ResultOf2`, `ArgsInterpreter`, `Argon2Helper`) wie in
[nexus421/DemoAiProject](https://github.com/nexus421/DemoAiProject) beschrieben.

Entry point: `Main.kt`. Packages unter `bayern.kickner.argos`:
- `config` — JSON-Config-Modelle, Laden/Validieren
- `db` — Exposed-Tabellen, SQLite/HikariCP-Setup
- `checks` — HTTP/TCP/Ping/DNS-Check-Implementierungen
- `notify` — Trigger-Logik, SMTP/Webhook-Versand
- `selfmonitor` — Heartbeat, Gap-Detection
- `scheduler` — Epoch-aligned Ticker
- `web` — Ktor-Routing, Basic Auth, Status-Pages

Spec/Plan: `docs/superpowers/plans/2026-09-12-argos-implementation-plan.md`.

## Commands

Gradle-Wrapper verwenden (`./gradlew`), keine System-Gradle-Installation.

```
./gradlew build
./gradlew run
./gradlew run --args="configPath=config.json"
./gradlew test
./gradlew test --tests "bayern.kickner.argos.config.ConfigLoaderTest"
```

## Toolchain

- Kotlin JVM Plugin 2.4.10, Ktor-Plugin 3.5.2.
- JVM-Toolchain: Amazon Corretto 25, Vendor gepinnt über `JvmVendorSpec.AMAZON`.
- Gradle 9.6 via Wrapper.
- Test-Framework: Kotest (JUnit-Platform), Mockk für Mocks.
- Group/Koordinaten: `bayern.kickner:Argos:1.0-SNAPSHOT`.
- Zusätzliches Maven-Repo `nexus421MavenReleases` für `bayern.kickner:Klogger`/`KotNexLib`.

## Code style

- `.not()` statt `!`.
- Kleine `if`s ohne Klammern; Bedingung zuerst einer `val` zuweisen, dann verzweigen.
- `runCatching` als Standard; `try`/`catch` nur bei echt unterschiedlicher Recovery-Logik pro
  Exception-Typ, mit Kommentar.
- Sealed classes/interfaces statt nullable Felder plus Laufzeit-Checks (siehe `CheckConfig`,
  `TriggerDecision`).
- SQLite: `busy_timeout` immer vor `journal_mode=WAL`; Writes ausschließlich über
  `Dispatchers.IO.limitedParallelism(1)` (`db/Database.kt`).
- Ping/DNS-Checks brauchen Root-Rechte bzw. `-Dnetworkaddress.cache.ttl=0` — siehe
  `Security.setProperty(...)` in `Main.kt`.

## Nicht im Scope (bewusste Entscheidung)

- Kein Login/Session/2FA, keine Mutations-API.
- Kein Hot-Reload der Config — Änderung erfordert Neustart.
- Kein Docker-Support als Zielplattform.
```

- [ ] **Step 4: Commit**

```bash
git add scripts/argos.service CLAUDE.md
git commit -m "chore: systemd-Unit und CLAUDE.md"
```

---

## Self-Review

**Spec-Abdeckung:**
- Monitor-ID + verwaiste-DB-ID-Info → Task 2 (ID-Feld), verwaiste-ID-Anzeige folgt als kleine Erweiterung in Task 9 (`respondText`-Zweig), im Plan nicht separat aufgeführt — **Lücke:** eigener Task für den DB/Config-ID-Abgleich fehlt, siehe Hinweis unten.
- Basic Auth pro Status-Page → Task 9
- Retention konfigurierbar, In-Process-Cleanup → Task 8 (`cleanupOldHistory`)
- HTTP/TCP/Ping/DNS-Checks → Task 4
- SMTP/Webhook, Flapping-Schutz, Recovery → Task 5, Task 6
- Self-Monitoring (Heartbeat, Gap-Detection, Start-Notification) → Task 7, Task 11
- Config-Generator → Task 10
- systemd mit `Restart=always` → Task 12

**Bekannte Lücke:** Der Abgleich "IDs in der DB ohne aktuelle Config-Zuordnung" (Info-Hinweis im UI) ist im Plan nicht als eigener Task ausgeführt. Nachtrag bei Bedarf: In `WebModule.kt` beim Start einmalig `CheckHistoryTable.slice(monitorId).selectAll().distinct()` gegen `config.monitors.map { it.id }` abgleichen und die Differenz als zusätzliche Zeile auf `/` anzeigen.

**Nicht in diesem Environment kompiliert:** Dieser Plan wurde ohne Zugriff auf Maven Central erstellt (Sandbox-Netzwerk erlaubt kein `repo1.maven.org`) — der erste `./gradlew build` sollte auf Typos/API-Abweichungen geprüft werden, insbesondere bei den Klogger/KotNexLib-Importpfaden (siehe Global Constraints).

## Execution Handoff

Plan liegt unter `argos-implementation-plan.md`. Für die Ausführung in Claude Code, Datei nach `docs/superpowers/plans/2026-09-12-argos-implementation-plan.md` legen und eine der beiden Optionen nutzen:

1. **Subagent-Driven** (`superpowers:subagent-driven-development`) — frischer Subagent pro Task, Review dazwischen
2. **Inline Execution** (`superpowers:executing-plans`) — Tasks in einer Session abarbeiten, Checkpoints zwischen Tasks
