# Argos

**Argos** is a lightweight, self-hosted JVM infrastructure and service monitoring system distributed as a single executable Fat JAR.

Built with Kotlin and Ktor, Argos follows a strict KISS (Keep It Simple, Stupid) philosophy:

- **No Docker required** — runs directly on a Java 25 runtime on Linux, typically as a native `systemd` service.
- **No login, no sessions, no mutation API** — monitors and channels are statically defined in a single, human-readable JSON configuration file. Changing it means restarting the process.
- **Resilient & crash-proof** — boots even with a missing or invalid configuration (or an unusable data directory) and offers an interactive client-side setup helper. In that state `/` answers **503** naming the category of the problem (details are in the log), so an external health check notices that monitoring is not running.
- **Low footprint** — Kotlin Coroutines with an epoch-aligned ticker, SQLite in WAL mode with serialized single-threaded writes, and lightweight server-side rendered status pages. Runs comfortably in 256 MiB of heap.

---

## Features

- **Multi-protocol checks**
  - **HTTP/HTTPS** — custom method and headers, expected status codes, body regex (applied to the first 1 MiB of the response), configurable redirect following.
  - **TCP** — socket connection on any port.
  - All latencies are measured with `System.nanoTime()` and stored with sub-millisecond precision (`0.04 ms` on a LAN instead of `0 ms`).
  - **ICMP / Ping** — via the system `ping` binary (iputils; works unprivileged, reports the real round-trip time with microsecond resolution). Without a `ping` binary the check fails.
  - **DNS** — name resolution with optional expected IP address.
  - Every check honours `timeoutSeconds`, including the name resolution step.
- **Alerting & notification channels**
  - **SMTP** — Jakarta Mail with required STARTTLS (default), implicit TLS, or plaintext for trusted internal relays; optional SMTP AUTH; finite connect/read/write timeouts.
  - **Webhooks** — HTTP requests with placeholder templating (`{{status}}` = `DOWN`/`UP`/`SYSTEM`, `{{monitorId}}`, `{{monitorName}}`, `{{subject}}`, `{{body}}`), automatic JSON escaping for `application/json` bodies, 15 s delivery timeout, non-2xx answers count as failures.
  - **Delivery guarantee** — channels are addressed in parallel, each with four attempts (10/30/60 s apart). Every monitor alert is stored in SQLite together with the check result and removed only once all channels confirmed it; whatever is left is retried every 5 minutes and after a restart, so neither a slow SMTP server nor a restart during delivery loses an alert.
- **Trigger logic**
  - **Flapping protection** — a DOWN alert fires after `flappingThreshold` consecutive failures, exactly once.
  - **Recovery notification** — fires on the first successful check after a DOWN and names the start and length of the outage.
  - Trigger state is **restored from the history on restart**, so a restart neither repeats a DOWN alert nor swallows a recovery.
- **Self-monitoring**
  - Heartbeat in SQLite every 30 s, at start and at clean shutdown.
  - On start: a gap of at least `heartbeatGapMinutesThreshold` since the last heartbeat is reported as *unexpected offline period*.
  - At runtime: a scheduler pause (suspend, VM freeze, clock jump) is logged; a pause of at least the same threshold is reported as *scheduler paused*. Missed checks are never replayed.
  - System events (`Argos started`, `Argos: unexpected offline period`, `Argos: scheduler paused`) go to every channel with `systemEvents: true`.
- **Status pages**
  - Server-side rendered HTML (`kotlinx.html`), no JavaScript: name, UP/DOWN/UNKNOWN, last latency, time of the last check (`dd.MM.yyyy HH:mm:ss UTC`); auto-refresh every 30 s; light and dark theme.
  - 30-day history per monitor as an inline SVG bar chart: one bar per UTC day, height = average latency of the successful checks, red as soon as one check failed that day (full height when none succeeded), grey for days without data; hovering a bar shows date, uptime, failed/total and the average.
  - Optional per-page **HTTP Basic Auth** with **Argon2id** password hashes. Error details (which name internal hosts and ports) are shown on authenticated pages only.
- **Configuration editor**
  - Browser UI at `/setup` that covers the whole `config.json`: monitors, SMTP and webhook channels, status pages (including Basic Auth) and the general settings, each field with a short explanation. Start from scratch or load an existing file, then download or copy the result.
  - Runs entirely in the browser: no request ever carries the configuration to the server, and nothing is stored there — the file only ever exists on your device.
  - Validates live with the same rules as the server (IDs, ranges, cross references, regexes, unknown fields), so the download is disabled until Argos would accept the file.
- **Strict configuration validation**
  - IDs, ranges, cross references, URL schemes, e-mail addresses, IP literals, regexes, TLS modes and password hashes are validated at start, and unknown fields are rejected with their path. A rejected configuration is reported with the full list of issues in the log; nothing that could crash the scheduler or silently drop an alert is accepted.

---

## Tech Stack

- **Language:** Kotlin 2.4.10
- **Runtime:** Java 25 (Amazon Corretto toolchain, pinned in Gradle)
- **Web & client:** Ktor 3.5.2 (CIO server and client engines, HTML builder, Basic Auth)
- **Database:** Exposed 1.5.0 + SQLite JDBC 3.53.4.0 + HikariCP 6.3.0 — WAL mode, 5 s busy timeout, single-threaded writer
- **Mail:** Jakarta Mail 2.0.2
- **Logging & utilities:** `bayern.kickner:Klogger:0.1.0` (application log, stdout), SLF4J Simple (framework warnings), `bayern.kickner:KotNexLib:4.4.1` (Argon2, CLI args, `ResultOf2`)
- **Tests:** Kotest 5.9.1, Ktor `MockEngine` / `testApplication`; GraalJS 25 (test scope only) runs the setup page's `config-model.js` inside Kotest to check its validation against the server's

---

## Architecture

```
                    +------------------------------------------+
                    |                Argos                     |
                    |  +------------------------------------+  |
                    |  |  Main (config, DNS TTL = 0, wiring) |  |
                    |  +-----------------+------------------+  |
                    +--------------------|---------------------+
     +--------------+--------------------+----------------+----------------+
     |                                   |                                 |
     v                                   v                                 v
+------------+                  +------------------+              +---------------+
| Web Server |                  |    Scheduler     |  heartbeat   | SQLite (WAL)  |
| (Ktor CIO) |                  | (epoch-aligned,  +------------->+---------------+
+-----+------+                  |  no replay,      |  results     | check_history |
      |                         |  no overlap)     +------------->| self_monitor  |
      +---> /setup (static UI)  +--------+---------+   alerts     | pending_alert |
      |                                  |          +------------->| schema_version|
      |                                  |                         +-------+-------+
      +---> /  (health: 200/503)         |                                |
      +---> /status/{id} (opt. Basic     +---> HTTP / TCP / Ping / DNS    |
            Auth, reads history) <----------------------------------------+
                                                 |
                                                 v
                                      +--------------------+
                                      | Trigger logic      |
                                      | (flapping, restore)|
                                      +----------+---------+
                                                 v
                                      +--------------------+
                                      | Notification       |
                                      | dispatcher         |
                                      +----+----------+----+
                                           v          v
                                         SMTP      Webhook
```

### Key design decisions

1. **SQLite WAL & one writer.** WAL lets status pages read while checks write. All writes go through `Dispatchers.IO.limitedParallelism(1)`, reads through a bounded view of their own; `busy_timeout`, `journal_mode` and `synchronous=NORMAL` are set as driver properties (sqlite-jdbc executes only the first statement of `connectionInitSql`). The schema carries a version; older databases are migrated at start, a database from a newer build is refused.
2. **Epoch-aligned scheduler without replay.** Every monitor runs once right after start, then has a due second on the epoch grid (multiples of `intervalSeconds`); monitors with the same interval therefore run in the same second. A late or skipped tick never loses a run. After a pause each monitor runs once and re-joins the grid. A monitor whose previous check is still running skips the tick — checks of one monitor never overlap; notifications are delivered in a separate scope outside that lock.
3. **Alerts are queued, not fired.** A check result and the alert it triggers are stored in one transaction (`pending_alert`); the delivery removes the row once every channel confirmed, otherwise the row keeps the open channels and the scheduler retries. Blocking network calls (checks, mail) and database reads run on separate thread pools, so a DNS outage cannot starve the status pages.
4. **Hard validation, soft start.** Anything that could crash the scheduler or drop an alert is rejected at load time, including unknown fields. The process still starts (bootstrap state, `/` = 503) so the setup page is reachable and `systemd` does not enter a restart loop.
5. **Immutability.** No runtime configuration changes; edit `config.json` and restart.

---

## Configuration

Argos reads its configuration from `./config.json` or the file given as `configPath=<path>`.

### Example `config.json`

```json
{
  "monitors": [
    {
      "id": "web-api",
      "name": "Public API",
      "intervalSeconds": 60,
      "timeoutSeconds": 10,
      "check": {
        "type": "http",
        "url": "https://api.example.com/health",
        "method": "GET",
        "headers": { "Authorization": "Bearer <token>" },
        "expectedStatusCodes": [200],
        "bodyRegex": "\"status\"\\s*:\\s*\"UP\"",
        "followRedirects": true
      },
      "notificationChannelIds": ["email-ops", "slack-alerts"]
    },
    {
      "id": "db-server",
      "name": "Database Port",
      "intervalSeconds": 30,
      "timeoutSeconds": 5,
      "check": { "type": "tcp", "host": "db.internal.example.com", "port": 5432 }
    },
    {
      "id": "gateway-ping",
      "name": "Internal Gateway",
      "intervalSeconds": 10,
      "timeoutSeconds": 2,
      "check": { "type": "ping", "host": "192.168.1.1" }
    },
    {
      "id": "dns-check",
      "name": "Domain Resolution",
      "intervalSeconds": 300,
      "timeoutSeconds": 5,
      "check": { "type": "dns", "hostname": "app.example.com", "expectedIp": "203.0.113.10" },
      "notificationChannelIds": []
    }
  ],
  "smtpChannels": [
    {
      "id": "email-ops",
      "host": "smtp.example.com",
      "port": 587,
      "username": "argos@example.com",
      "password": "secretpassword",
      "from": "argos@example.com",
      "to": ["ops@example.com"],
      "tls": "starttls",
      "systemEvents": true
    }
  ],
  "webhookChannels": [
    {
      "id": "slack-alerts",
      "url": "https://hooks.slack.com/services/XXX/YYY/ZZZ",
      "method": "POST",
      "headers": { "Content-Type": "application/json" },
      "bodyTemplate": "{\"text\": \"*{{subject}}*\\n{{body}}\"}",
      "systemEvents": true
    }
  ],
  "statusPages": [
    {
      "id": "public",
      "name": "Service Status",
      "monitorIds": ["web-api", "dns-check"]
    },
    {
      "id": "internal",
      "name": "Internal Status",
      "monitorIds": ["web-api", "db-server", "gateway-ping"],
      "basicAuth": {
        "username": "admin",
        "passwordHash": "JGFyZ29uMmlkJHY9MTkkbT02NTUzNix0PTMscD0xJEdOZXF5YjBBcDI4TzhraExXK2VZNnc9PSR3OE1kWHlWQzhhYTZTMU4vOG5lOWdPSTRHbWZDSWgyS3VraVdPSzVoWEprPQ=="
      }
    }
  ],
  "retentionDays": 365,
  "flappingThreshold": 3,
  "heartbeatGapMinutesThreshold": 2,
  "dataDir": "./data",
  "webHost": "0.0.0.0",
  "webPort": 8080
}
```

The example hash is for the password `change-me` — generate your own with `hashPassword=` (see below).

### Field reference

| Field | Default | Notes |
|---|---|---|
| `monitors[].id` | — | `[A-Za-z0-9_.-]`, 1–64 chars, unique. Used as history key, so renaming a monitor orphans its history. |
| `monitors[].intervalSeconds` | — | > 0. Monitors run at epoch multiples of this value. |
| `monitors[].timeoutSeconds` | — | > 0 and < `intervalSeconds`. Covers name resolution as well. |
| `monitors[].check.type` | — | `http`, `tcp`, `ping`, `dns`. |
| `check.url`, `method`, `headers`, `expectedStatusCodes`, `bodyRegex`, `followRedirects` | `GET`, `{}`, `[200]`, none, `true` | HTTP. `url` must start with `http://` or `https://`, `method` is a plain token such as `GET`, status codes are 100–599. `bodyRegex` must compile; it is matched against the first 1 MiB of the body. Without `bodyRegex` the body is not read. Redirects are followed for GET/HEAD only. |
| `check.host`, `check.port` | — | TCP (`port` 1–65535) and Ping (`host` only). Hosts are hostnames or IPv4/IPv6 literals (zone IDs allowed) and must not start with `-`. |
| `check.hostname`, `check.expectedIp` | — | DNS. `expectedIp` must be an IPv4 or IPv6 literal and is compared by address, so `2001:db8::1` matches however the resolver spells it. |
| `monitors[].notificationChannelIds` | `null` | `null`/omitted = all channels; `[]` = no alerts (status-page-only monitor); otherwise IDs must exist. |
| `smtpChannels[].tls` | `"starttls"` | `starttls` (required — the connection fails if the server does not offer it), `ssl` (implicit TLS, e.g. port 465), `none` (plaintext). The certificate hostname is always verified. |
| `smtpChannels[].username` | — | SMTP AUTH is used when non-empty. `from` and every `to` entry must look like `user@host` or `Name <user@host>`; `to` must contain at least one address. |
| `webhookChannels[].url`, `method`, `headers` | —, `POST`, `{}` | `url` must start with `http://` or `https://`, `method` is a plain token. With `Content-Type: application/json` all placeholder values are JSON-escaped. |
| `webhookChannels[].bodyTemplate` | — | Placeholders: `{{status}}` (`DOWN`/`UP`/`SYSTEM`), `{{subject}}`, `{{body}}`, `{{monitorId}}`, `{{monitorName}}` (the last two only for monitor alerts). |
| `*.systemEvents` | `true` | Whether the channel receives system events. |
| `statusPages[].id` | — | Becomes the path `/status/<id>`; same character rules as monitor IDs. `monitorIds` must exist. |
| `statusPages[].basicAuth` | none | `username` + `passwordHash` (Argon2id, generate with `hashPassword=` — anything else is rejected at start). Protected pages also show error details. |
| `retentionDays` | `365` | History older than this is deleted at start and daily at 00:00 UTC, in batches. |
| `flappingThreshold` | `3` | Consecutive failures before a DOWN alert. |
| `heartbeatGapMinutesThreshold` | `2` | Gap length that counts as unexpected downtime / scheduler pause. |
| `dataDir` | `"./data"` | Created if missing; holds `argos.db` (+ `-wal`/`-shm` while running). Relative to the working directory. |
| `webHost`, `webPort` | `"0.0.0.0"`, `8080` | Bind address. Argos serves plain HTTP — put a TLS-terminating reverse proxy in front and bind to `127.0.0.1` when the proxy runs on the same host. |

Unknown fields are rejected with their path (`Unknown field 'basicauth' in statusPages[1]`) — a typo would otherwise silently fall back to the default, here a public page. The config file contains SMTP passwords and webhook tokens: keep it out of version control (`config.json` is git-ignored) and restrict it with `chmod 600`.

---

## Getting started

### Prerequisites

- Java 25 (Amazon Corretto recommended; Gradle downloads the toolchain automatically)
- The included Gradle wrapper (`./gradlew`)

### Build the Fat JAR

```bash
./gradlew build
```

Compiles, runs the test suite and produces `build/libs/argos.jar`.

### Run tests

```bash
./gradlew test
```

### Run locally

Without a configuration (bootstrap mode):

```bash
java -jar build/libs/argos.jar
```

Open `http://localhost:8080/setup`, add your monitors, channels and status pages and download `config.json` (the page never sends it to Argos). `/` answers 503 until a valid configuration is loaded.

With a configuration:

```bash
java -jar build/libs/argos.jar configPath=/path/to/config.json
```

Generate a password hash for a protected status page (the leading space keeps the password out of your shell history when `HISTCONTROL=ignorespace` is set):

```bash
 java -jar build/libs/argos.jar hashPassword=mySecret
```

Put the printed value into `statusPages[].basicAuth.passwordHash`.

---

## Production deployment with systemd

### Quick install (Debian/Ubuntu)

[`scripts/install.sh`](scripts/install.sh) sets everything up in one go. Create the directory, then run the installer inside it:

```bash
sudo mkdir -p /opt/argos && cd /opt/argos && curl -fsSL https://raw.githubusercontent.com/nexus421/Argos/main/scripts/install.sh | sudo bash
```

The installer

- checks for Java 25+, `curl` and systemd and stops before touching anything if one is missing;
- downloads `argos.jar` from the latest GitHub release (the release asset must be named `argos.jar`, which is what `./gradlew build` produces);
- writes `/etc/systemd/system/argos.service` with the current directory as working directory and the user who invoked `sudo` as service user (`... | sudo bash -s -- --user NAME` picks another existing user);
- enables and starts the service and verifies that it is running;
- writes a `README.md` with the operating instructions (service commands, first-time configuration, update, rollback, backup, uninstall) into the directory.

Running the same command again in the same directory **updates** Argos: `config.json` and `data/` are kept, the previous jar stays as `argos.jar.old`, the service is restarted. Without a `config.json` the service starts in bootstrap mode — open `http://<host>:8080/setup`, download the configuration into the directory and restart.

### Manual installation

1. Install the JAR and the configuration:
   ```bash
   sudo mkdir -p /opt/argos
   sudo cp build/libs/argos.jar /opt/argos/argos.jar
   sudo cp config.json /opt/argos/config.json
   sudo chmod 600 /opt/argos/config.json
   ```

2. Create `/etc/systemd/system/argos.service` (this is the unit the installer generates):
   ```ini
   [Unit]
   Description=Argos Monitoring
   After=network-online.target
   Wants=network-online.target

   [Service]
   Type=simple
   User=root
   WorkingDirectory=/opt/argos
   # --enable-native-access: sqlite-jdbc loads native code; JDK 25 warns, later JDKs refuse without it
   # -Xmx256m: plenty for Argos, prevents the JVM from claiming 1/4 of the host RAM
   ExecStart=/usr/bin/java --enable-native-access=ALL-UNNAMED -Xmx256m -jar /opt/argos/argos.jar configPath=/opt/argos/config.json
   Restart=always
   RestartSec=5

   [Install]
   WantedBy=multi-user.target
   ```

3. Enable and start it:
   ```bash
   sudo systemctl daemon-reload
   sudo systemctl enable --now argos.service
   ```

4. Verify:
   ```bash
   sudo systemctl status argos.service
   sudo journalctl -u argos.service -f
   curl -i http://127.0.0.1:8080/
   ```
   `ERROR/Main` lines in the journal mean the configuration was rejected; `/` then returns 503 and the journal lists the issues.

Running Argos as **`root`** is not a technical requirement: ping monitors use the system `ping` binary (`/usr/bin/ping`, iputils), which carries the `cap_net_raw` file capability and therefore works for any user. To run unprivileged, set `User=` to an existing user that owns the directory — nothing else changes. Without a `ping` binary every ping check fails (`No ping binary found`) and an error is logged at start — install `iputils-ping`. At start Argos sends one real echo request to loopback and logs an error if that fails, plus an info line naming the binary.

The JVM is started with `--enable-native-access=ALL-UNNAMED` (sqlite-jdbc loads native code) and `-Xmx256m`.

### Operations

- **Health check:** `GET /` → `200` while a valid configuration is loaded, `503` otherwise (invalid or unreadable configuration, or a `dataDir` that cannot be opened). The body names only the category; the details are in the journal.
- **Reverse proxy:** terminate TLS there and rate-limit `/status/*` if the pages are reachable from the internet (Argon2 verification runs one at a time, ~64 MiB and ~100 ms each, so a limit such as nginx `limit_req` keeps the pages responsive under abuse). Authenticated pages are sent with `Cache-Control: no-store`.
- **Backup:** `<dataDir>/argos.db` is a SQLite database in WAL mode. Back it up with `sqlite3 argos.db ".backup argos-backup.db"` (safe while running) rather than copying the raw file. The database holds history, the heartbeat and queued alerts; losing it loses the history, not the configuration.
- **Alert delivery:** channels are notified in parallel, four attempts over ~100 s. A monitor alert is stored in `pending_alert` together with the check result and removed once every channel confirmed; what is left is retried every 5 minutes and at the next start, alerts older than 24 h are dropped with an error log. The DOWN body starts with the detection time (`Detected at <UTC>.`), the UP body with `Recovered. Down since <UTC> (<duration>).`, so a late delivery is recognisable. Only the order of a successful first delivery is guaranteed: a DOWN still in retry can be overtaken by a fast UP.
- **First run:** every monitor is checked once right after start, then on its epoch grid.
- **Schema:** `argos.db` carries a `schema_version`; older databases are migrated on start, a database from a newer build is refused (the process starts in bootstrap mode with `/` = 503).
- **Logging:** application log via Klogger on stdout, framework warnings (Ktor, HikariCP, Exposed) via SLF4J at WARN and above; both land in the journal.
- **Ping details:** `ping -c 1 -W <timeoutSeconds> -n -- <host>` with `LC_ALL=C`; UP/DOWN comes from the exit code (0 reply, 1 no reply or ICMP error, 2 tool error such as an unknown host), the latency from the tool's `time=` field. A process that outlives the timeout is killed. Requires iputils (`ping -V`), present on every mainstream distribution (BusyBox ping is not supported). Do not set `NoNewPrivileges=true` in the unit when running unprivileged — it disables the binary's file capability; the startup probe would report `Operation not permitted`.
- **Shutdown:** `SIGTERM` stops the HTTP server, cancels running checks, waits up to 30 s for alert deliveries in flight, writes a final heartbeat and checkpoints the WAL. Planned stops longer than `heartbeatGapMinutesThreshold` are still reported as an unexpected offline period on the next start — there is no maintenance mode.

---

## License

Copyright (c) 2026 bayern.kickner.
All rights reserved.
