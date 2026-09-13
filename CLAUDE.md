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

Spec/Plan: `konzept/argos-implementation-plan.md`.
Einwände/Anmerkungen: `konzept/argos-einwaende.md`.

## Commands

Gradle-Wrapper verwenden (`./gradlew`), keine System-Gradle-Installation.

```
./gradlew build
./gradlew run
./gradlew run --args="configPath=config.json"
./gradlew run --args="hashPassword=geheim"   # Argon2-Hash für statusPages[].basicAuth.passwordHash
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
- SQLite: `busy_timeout` immer vor `journal_mode=WAL` — beides als `SQLiteConfig`-Properties, **nicht**
  über `connectionInitSql` (sqlite-jdbc führt dort nur das erste Statement aus). Writes ausschließlich
  über `Dispatchers.IO.limitedParallelism(1)` (`db/Database.kt`); History-Zugriffe über `db/CheckHistory.kt`.
- Blockierende JDK-Netzaufrufe (DNS, Socket, ICMP) nur über `checks/BlockingTimeout.kt`, damit
  `timeoutSeconds` auch die Namensauflösung deckt.
- Scheduler: kein Nachholen verpasster Ticks (`DueTracker`), kein Overlap pro Monitor; Pausen werden
  über `classifyTickGap` gemeldet.
- Config-Validierung ist hart: alles, was den Scheduler crashen oder Alerts still verschlucken könnte,
  lehnt `loadConfig` ab. Parse-Fehler nie mit JSON-Inhalt loggen (Secrets). Ohne gültige Config läuft der
  Server im Bootstrap-Zustand weiter, `/` antwortet dann 503 mit dem Grund (kein Exit, kein Restart-Loop).
- `runCatching` in Coroutinen immer mit `.rethrowCancellation()` (`Coroutines.kt`), sonst wird ein
  Shutdown als fehlgeschlagener Check/Versand geloggt.
- Öffentliche Status-Pages (ohne `basicAuth`) zeigen keine Fehlertexte — die nennen interne Hosts/Ports.
- Ping läuft unter Linux über `/usr/bin/ping` per `ProcessBuilder` (`checks/PingCheck.kt`, `PingBackend`):
  kein root nötig, echte RTT aus `time=`, UP/DOWN aus dem Exit-Code, `LC_ALL=C`, Host hinter `--`,
  `destroyForcibly` nach Timeout. JDK-`isReachable` nur als Fallback ohne Binary; das braucht CAP_NET_RAW,
  sonst still TCP-Port-7 (RST = „up", Drop = „down") — Probe dafür ist `CapEff` in `/proc/self/status`,
  nie `isReachable(loopback)`. Latenzen sind `Double`-Millisekunden aus `nanoTime`.
- DNS-Caching ist per `Security.setProperty(...)` in `Main.kt` abgeschaltet.

## Nicht im Scope (bewusste Entscheidung)

- Kein Login/Session/2FA, keine Mutations-API.
- Kein Hot-Reload der Config — Änderung erfordert Neustart.
- Kein Docker-Support als Zielplattform.
- Kein TLS im Webserver — Argos läuft hinter einem Reverse-Proxy, der TLS terminiert.
- systemd-Unit läuft bewusst als `root` (Entscheidung, technisch nicht mehr nötig), kein Jitter im
  Scheduler, Bind-Fehler beendet den Prozess.
