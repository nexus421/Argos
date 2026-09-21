# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

Argos ist ein Kotlin/JVM-Monitoring-Tool (`bayern.kickner`), Fat Jar, ohne Login/Docker. Nutzt
Klogger (Logging) und KotNexLib (`ResultOf2`, `ArgsInterpreter`) wie in
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
./gradlew test
./gradlew test --tests "bayern.kickner.argos.config.ConfigLoaderTest"
```

## Toolchain

- Kotlin JVM Plugin 2.4.10, Ktor-Plugin 3.5.2.
- JVM-Toolchain: Amazon Corretto 25, Vendor gepinnt über `JvmVendorSpec.AMAZON`.
- Gradle 9.6 via Wrapper.
- Test-Framework: Kotest (JUnit-Platform); Ktor `MockEngine` statt einer Mocking-Library.
- Group/Koordinaten: `bayern.kickner:Argos`, Version steht in `build.gradle.kts` (`version = "…"`).
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
  über `Dispatchers.IO.limitedParallelism(1)`, Reads über `readDispatcher` (`db/Database.kt`); History-Zugriffe
  über `db/CheckHistory.kt`, Alert-Queue über `db/PendingAlerts.kt`. `synchronous=NORMAL` ist bewusst (WAL).
- Blockierende JDK-Netzaufrufe (DNS, Socket, ICMP) nur über `checks/BlockingTimeout.kt`, damit
  `timeoutSeconds` auch die Namensauflösung deckt.
- Scheduler: erster Check jedes Monitors sofort beim Start, danach Epoch-Grid; kein Nachholen verpasster Ticks
  (`DueTracker`), kein Overlap pro Monitor (`inFlight` wird genau einmal im `finally` freigegeben); Pausen werden
  über `classifyTickGap` gemeldet.
- Config-Validierung ist hart: alles, was den Scheduler crashen oder Alerts still verschlucken könnte,
  lehnt `loadConfig` ab — auch unbekannte Keys (`Unknown field 'x' in path`). Parse-Fehler nie mit JSON-Inhalt
  loggen (Secrets). Ohne gültige Config (oder ohne nutzbares `dataDir`) läuft der Server im Bootstrap-Zustand
  weiter (`serveBootstrap` in Main.kt): nur `/setup` und 503 auf `/` mit der Kategorie, keine DB, kein Heartbeat —
  die Überwachungslücke wird nach dem nächsten gültigen Start als Gap gemeldet (kein Exit, kein Restart-Loop);
  Details stehen nur im Log.
  Neue Config-Regel = `ConfigLoader.validate` **und** `config-model.js` **und** ein `InvalidCase` in
  `ConfigModelJsTest`, mit identischem Meldungstext. Neues Feld = zusätzlich in `*_KEYS` / `KNOWN_KEYS`.
- Alerts nie direkt zustellen: `recordCheck` speichert Ergebnis + `pending_alert` atomar, Zustellung läuft im
  `notificationScope` (`Scheduler.deliverAlert`), Retry/Parallelität im `NotificationDispatcher`. Zeile löschen
  nur bei vollständiger Bestätigung, sonst auf die offenen Channels verengen. Wer zustellt, beansprucht die ID
  vorher in `deliveringAlerts` (`add` == true) — sonst liefert schon ein anderer; Redelivery liest die Zeile frisch
  (`pendingAlertById`).
- Schema-Änderungen: `SCHEMA_VERSION` erhöhen und Schritt in `migrations` (db/Database.kt) eintragen; neue
  Tabellen nur in `SchemaUtils.create` aufnehmen.
- Blockierende Aufrufe nur auf eigenen `Dispatchers.IO.limitedParallelism`-Views (Checks, DB-Reads, Mail);
  nie auf dem nackten `Dispatchers.IO`.
- Ktor-Shutdown-Hook ist per Property aus (`io.ktor.server.engine.ShutdownHook=false`); die Stop-Reihenfolge
  (Server → Checks → Zustellungen bis 30 s → Heartbeat → DB) steht in Main.kt.
- Ktor-CIO-Client für Checks: `requestTimeout = 0`, `connectTimeout = INFINITE` — nur das `withTimeout` des
  Checks darf einen Request beenden (`checks/HttpCheck.kt`). Derselbe Client (`clientWithRedirects`) bedient auch
  die Webhooks: Ktors eigene Timeout-Exceptions tragen die volle URL (Token) in der Message, die im Log landen würde.
  Keinen dritten `HttpClient` anlegen. Validierungsmeldungen enthalten ebenfalls keine URLs.
- `runCatching` in Coroutinen immer mit `.rethrowCancellation()` (`Coroutines.kt`), sonst wird ein
  Shutdown als fehlgeschlagener Check/Versand geloggt.
- Öffentliche Status-Pages (ohne `basicAuth`) zeigen keine Fehlertexte — die nennen interne Hosts/Ports.
- Zielplattform ist ausschließlich Linux — kein Code für Windows/macOS.
- Ping läuft über `/usr/bin/ping` (iputils) per `ProcessBuilder` (`checks/PingCheck.kt`, `defaultPingBinary`):
  kein root nötig, echte RTT aus `time=`, UP/DOWN aus dem Exit-Code, `LC_ALL=C`, Host hinter `--`,
  `destroyForcibly` nach Timeout. Ohne Binary schlägt der Check fehl (kein JDK-`isReachable`-Fallback: der
  braucht CAP_NET_RAW, sonst still TCP-Port-7). Latenzen sind `Double`-Millisekunden aus `nanoTime`.
- DNS-Caching ist per `Security.setProperty(...)` in `Main.kt` abgeschaltet.
- Die JVM läuft in UTC (`TimeZone.setDefault` als erste Zeile in `main`, Tests per `-Duser.timezone=UTC` in
  `build.gradle.kts`): Exposed schreibt SQLite-Zeitstempel als Text in der JVM-Default-Zone — nur in UTC bleibt die
  Sortierung über DST-Wechsel korrekt und `date(timestamp)` liefert UTC-Tage. Anzeige ebenfalls UTC mit Suffix.
- Status-Page-Verlauf: `dailySummaries` (db/CheckHistory.kt) aggregiert pro Tag in SQL (`GROUP BY date(timestamp)`),
  `StatusService` polstert auf `HISTORY_DAYS` (Tage ohne Daten = `checks == 0`) und cacht das Ergebnis `HISTORY_CACHE_TTL`
  lang pro Monitor (~12 ms pro Query bei Minuten-Checks — ungecacht wäre jede öffentliche Seite ein CPU-Verstärker),
  `web/HistoryChart.kt` rendert das
  Inline-SVG — nur Zahlen/Daten im Markup, deshalb `raw` auch auf öffentlichen Seiten erlaubt. Kein JavaScript.
- Config-Editor unter `/setup` (`resources/static/`, via `staticResources`): rein clientseitig, deckt die ganze
  `AppConfig` ab, lädt/speichert nie etwas am Server (Upload = `FileReader`, Download = Blob). `config-model.js`
  ist DOM-frei und spiegelt `ConfigLoader.validate` samt Default-Werten und Meldungstexten 1:1 — jede neue
  Regel/jedes neue Feld dort nachziehen. Zusätzlich fängt es ab, was der Server erst als Parse-Fehler ablehnt
  (Enum-Werte, Ganzzahlen, Booleans), koerziert Zahlen-Strings wie der lenient Server-Parser und wirft bei
  falscher Struktur (`normalize`) statt die Seite zu crashen; `ConfigModelJsTest` führt die Datei per GraalJS (nur Test-Scope) aus
  und prüft sie gegen `loadConfig` und das README-Beispiel. `editor.js` (DOM) wird im Browser geprüft.
  Ressourcen-Pfade in `index.html` absolut (`/setup/…`), weil `/setup` ohne Slash ausgeliefert wird.
  Download ist gesperrt, solange die Validierung Fehler meldet; UI-Sprache Englisch.
- Status-Page-Passwörter (`basicAuth.password`) stehen bewusst im Klartext in der Config — wie die SMTP-Passwörter und
  Webhook-Tokens daneben; `chmod 600` ist der Schutz. Vergleich in `WebModule.kt` per `MessageDigest.isEqual`, beide
  Felder immer (non-short-circuit `and`). Kein Hashing (Argon2 bis 0.1.0 entfernt: eigener CLI-Modus, 64 MiB pro
  Login-Versuch, Editor konnte das Feld nicht befüllen).
- Deployment: `scripts/install.sh` (Debian/systemd, `curl … | sudo bash` oder `sudo ./install.sh`) installiert ins
  aktuelle Verzeichnis, lädt `argos.jar` vom neuesten GitHub-Release (`nexus421/Argos`, Asset muss `argos.jar`
  heißen), erzeugt Unit (Service-User = `$SUDO_USER`, `--user` überschreibt; root technisch nicht nötig) und eine
  README mit Betriebsanleitung. Erneuter Lauf = Update. Die Unit existiert nur im Skript (`render_unit`), keine
  separate `.service`-Datei.
  Das Skript nie lokal ausführen — Test in einer Debian-VM.

## Nicht im Scope (bewusste Entscheidung)

- Kein Login/Session/2FA, keine Mutations-API — der Config-Editor unter `/setup` ist keine: er erzeugt die
  Datei nur im Browser, der Server bekommt sie nie zu sehen.
- Kein Hot-Reload der Config — Änderung erfordert Neustart.
- Kein Docker-Support als Zielplattform.
- Kein TLS im Webserver — Argos läuft hinter einem Reverse-Proxy, der TLS terminiert.
- Kein Jitter im Scheduler, Bind-Fehler beendet den Prozess.
