# Argos

**Argos** is a lightweight, self-hosted JVM infrastructure and service monitoring system distributed as a single executable Fat JAR.

Built with Kotlin and Ktor, Argos follows a strict KISS (Keep It Simple, Stupid) philosophy:

- **No Docker required** — runs directly on a Java 25 runtime (Linux, macOS, Windows) or as a native `systemd` service.
- **No login, no sessions, no mutation API** — monitors and channels are statically defined in a single, human-readable JSON configuration file. Changing it means restarting the process.
- **Resilient & crash-proof** — boots even with a missing or invalid configuration and offers an interactive client-side setup helper. In that state `/` answers **503** with the reason, so an external health check notices that monitoring is not running.
- **Low footprint** — Kotlin Coroutines with an epoch-aligned ticker, SQLite in WAL mode with serialized single-threaded writes, and lightweight server-side rendered status pages. Runs comfortably in 256 MiB of heap.

---

## Features

- **Multi-protocol checks**
  - **HTTP/HTTPS** — custom method and headers, expected status codes, body regex (applied to the first 1 MiB of the response), configurable redirect following.
  - **TCP** — socket connection on any port.
  - All latencies are measured with `System.nanoTime()` and stored with sub-millisecond precision (`0.04 ms` on a LAN instead of `0 ms`).
  - **ICMP / Ping** — on Linux via the system `ping` binary (works unprivileged, reports the real round-trip time with microsecond resolution); falls back to `InetAddress.isReachable` where no `ping` is found.
  - **DNS** — name resolution with optional expected IP address.
  - Every check honours `timeoutSeconds`, including the name resolution step.
- **Alerting & notification channels**
  - **SMTP** — Jakarta Mail with required STARTTLS (default), implicit TLS, or plaintext for trusted internal relays; optional SMTP AUTH; finite connect/read/write timeouts.
  - **Webhooks** — HTTP requests with placeholder templating (`{{status}}` = `DOWN`/`UP`/`SYSTEM`, `{{monitorId}}`, `{{monitorName}}`, `{{subject}}`, `{{body}}`), automatic JSON escaping for `application/json` bodies, 15 s delivery timeout, non-2xx answers are logged as failures.
- **Trigger logic**
  - **Flapping protection** — a DOWN alert fires after `flappingThreshold` consecutive failures, exactly once.
  - **Recovery notification** — fires on the first successful check after a DOWN.
  - Trigger state is **restored from the history on restart**, so a restart neither repeats a DOWN alert nor swallows a recovery.
- **Self-monitoring**
  - Heartbeat in SQLite every 30 s, at start and at clean shutdown.
  - On start: a gap of at least `heartbeatGapMinutesThreshold` since the last heartbeat is reported as *unexpected offline period*.
  - At runtime: a scheduler pause (suspend, VM freeze, clock jump) is logged; a pause of at least the same threshold is reported as *scheduler paused*. Missed checks are never replayed.
  - System events (`Argos started`, `Argos: unexpected offline period`, `Argos: scheduler paused`) go to every channel with `systemEvents: true`.
- **Status pages**
  - Server-side rendered HTML (`kotlinx.html`), no JavaScript: name, UP/DOWN/UNKNOWN, last latency, time of the last check; auto-refresh every 30 s; light and dark theme.
  - Optional per-page **HTTP Basic Auth** with **Argon2id** password hashes. Error details (which name internal hosts and ports) are shown on authenticated pages only.
- **Configuration generator**
  - Browser UI at `/setup` for creating and editing the monitor list of a `config.json`. Channels, status pages and all other fields of a loaded file are preserved unchanged; monitor IDs survive a round trip.
- **Strict configuration validation**
  - IDs, ranges, cross references, regexes and TLS modes are validated at start. A rejected configuration is reported with the full list of issues; nothing that could crash the scheduler or silently drop an alert is accepted.

---

## Tech Stack

- **Language:** Kotlin 2.4.10
- **Runtime:** Java 25 (Amazon Corretto toolchain, pinned in Gradle)
- **Web & client:** Ktor 3.5.2 (CIO server and client engines, HTML builder, Basic Auth)
- **Database:** Exposed 1.5.0 + SQLite JDBC 3.53.4.0 + HikariCP 6.3.0 — WAL mode, 5 s busy timeout, single-threaded writer
- **Mail:** Jakarta Mail 2.0.2
- **Logging & utilities:** `bayern.kickner:Klogger:0.1.0` (application log, stdout), SLF4J Simple (framework warnings), `bayern.kickner:KotNexLib:4.3.0` (Argon2, CLI args, `ResultOf2`)
- **Tests:** Kotest 5.9.1, Ktor `MockEngine` / `testApplication` (Mockk is declared but currently unused)

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
      +---> /setup (static UI)  +--------+---------+              +-------+-------+
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

1. **SQLite WAL & one writer.** WAL lets status pages read while checks write. All writes go through `Dispatchers.IO.limitedParallelism(1)`; `busy_timeout` and `journal_mode` are set as driver properties (sqlite-jdbc executes only the first statement of `connectionInitSql`).
2. **Epoch-aligned scheduler without replay.** Every monitor has a due second on the epoch grid (multiples of `intervalSeconds`); monitors with the same interval therefore run in the same second. A late or skipped tick never loses a run. After a pause each monitor runs once and re-joins the grid. A monitor whose previous check is still running skips the tick — checks of one monitor never overlap; notifications are delivered outside that lock.
3. **Hard validation, soft start.** Anything that could crash the scheduler or drop an alert is rejected at load time. The process still starts (bootstrap state, `/` = 503) so the setup page is reachable and `systemd` does not enter a restart loop.
4. **Immutability.** No runtime configuration changes; edit `config.json` and restart.

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
        "passwordHash": "<output of: java -jar argos.jar hashPassword=...>"
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

### Field reference

| Field | Default | Notes |
|---|---|---|
| `monitors[].id` | — | `[A-Za-z0-9_.-]`, 1–64 chars, unique. Used as history key, so renaming a monitor orphans its history. |
| `monitors[].intervalSeconds` | — | > 0. Monitors run at epoch multiples of this value. |
| `monitors[].timeoutSeconds` | — | > 0 and < `intervalSeconds`. Covers name resolution as well. |
| `monitors[].check.type` | — | `http`, `tcp`, `ping`, `dns`. |
| `check.url`, `method`, `headers`, `expectedStatusCodes`, `bodyRegex`, `followRedirects` | `GET`, `{}`, `[200]`, none, `true` | HTTP. `bodyRegex` must compile; it is matched against the first 1 MiB of the body. Without `bodyRegex` the body is not read. |
| `check.host`, `check.port` | — | TCP (`port` 1–65535) and Ping (`host` only). Hosts are hostnames or IPv4/IPv6 literals (zone IDs allowed) and must not start with `-`. |
| `check.hostname`, `check.expectedIp` | — | DNS. `expectedIp` must equal one of the resolved addresses (IPv4 or IPv6 text form). |
| `monitors[].notificationChannelIds` | `null` | `null`/omitted = all channels; `[]` = no alerts (status-page-only monitor); otherwise IDs must exist. |
| `smtpChannels[].tls` | `"starttls"` | `starttls` (required — the connection fails if the server does not offer it), `ssl` (implicit TLS, e.g. port 465), `none` (plaintext). The certificate hostname is always verified. |
| `smtpChannels[].username` | — | SMTP AUTH is used when non-empty. `to` must contain at least one address. |
| `webhookChannels[].headers` | `{}` | With `Content-Type: application/json` all placeholder values are JSON-escaped. |
| `webhookChannels[].bodyTemplate` | — | Placeholders: `{{status}}` (`DOWN`/`UP`/`SYSTEM`), `{{subject}}`, `{{body}}`, `{{monitorId}}`, `{{monitorName}}` (the last two only for monitor alerts). |
| `*.systemEvents` | `true` | Whether the channel receives system events. |
| `statusPages[].id` | — | Becomes the path `/status/<id>`; same character rules as monitor IDs. `monitorIds` must exist. |
| `statusPages[].basicAuth` | none | `username` + `passwordHash` (Argon2id, generate with `hashPassword=`). Protected pages also show error details. |
| `retentionDays` | `365` | History older than this is deleted at start and daily at 00:00 UTC, in batches. |
| `flappingThreshold` | `3` | Consecutive failures before a DOWN alert. |
| `heartbeatGapMinutesThreshold` | `2` | Gap length that counts as unexpected downtime / scheduler pause. |
| `dataDir` | `"./data"` | Created if missing; holds `argos.db` (+ `-wal`/`-shm` while running). Relative to the working directory. |
| `webHost`, `webPort` | `"0.0.0.0"`, `8080` | Bind address. Argos serves plain HTTP — put a TLS-terminating reverse proxy in front and bind to `127.0.0.1` when the proxy runs on the same host. |

Unknown fields are ignored. The config file contains SMTP passwords and webhook tokens: keep it out of version control (`config.json` is git-ignored) and restrict it with `chmod 600`.

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

Open `http://localhost:8080/setup`, add your monitors and download `config.json`. `/` answers 503 until a valid configuration is loaded.

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

1. Install the JAR and the configuration:
   ```bash
   sudo mkdir -p /opt/argos
   sudo cp build/libs/argos.jar /opt/argos/argos.jar
   sudo cp config.json /opt/argos/config.json
   sudo chmod 600 /opt/argos/config.json
   ```

2. Install and start the service unit:
   ```bash
   sudo cp scripts/argos.service /etc/systemd/system/argos.service
   sudo systemctl daemon-reload
   sudo systemctl enable --now argos.service
   ```

3. Verify:
   ```bash
   sudo systemctl status argos.service
   sudo journalctl -u argos.service -f
   curl -i http://127.0.0.1:8080/
   ```
   `ERROR/Main` lines in the journal mean the configuration was rejected; `/` then returns 503 with the list of issues.

The provided unit runs Argos as **`root`**. That is no longer a technical requirement: on Linux, ping monitors use the system `ping` binary (`/usr/bin/ping`, iputils), which carries the `cap_net_raw` file capability and therefore works for any user. To run unprivileged, add `User=argos` (after creating that user and giving it `/opt/argos`) — nothing else changes. Only where no `ping` binary exists does Argos fall back to `InetAddress.isReachable`, which needs `CAP_NET_RAW` (root or `AmbientCapabilities=CAP_NET_RAW`); without it the JDK silently probes TCP port 7 instead, which reports UP for hosts that answer with a reset and DOWN for hosts that drop the packet — nothing useful. At start Argos sends one real echo request to loopback with the selected backend and logs an error if that fails, plus an info line naming the backend.

The JVM is started with `--enable-native-access=ALL-UNNAMED` (sqlite-jdbc loads native code) and `-Xmx256m`.

### Operations

- **Health check:** `GET /` → `200` while a valid configuration is loaded, `503` otherwise. Point the proxy's health check or an external uptime monitor at it.
- **Reverse proxy:** terminate TLS there and rate-limit `/status/*` if the pages are reachable from the internet (Argon2 verification is limited to two parallel checks of ~64 MiB each, but a limit such as nginx `limit_req` keeps the pages responsive under abuse).
- **Backup:** `<dataDir>/argos.db` is a SQLite database in WAL mode. Back it up with `sqlite3 argos.db ".backup argos-backup.db"` (safe while running) rather than copying the raw file. The database only holds history and the heartbeat; losing it loses the history, not the configuration.
- **Logging:** application log via Klogger on stdout, framework warnings (Ktor, HikariCP, Exposed) via SLF4J at WARN and above; both land in the journal.
- **Ping details:** `ping -c 1 -W <timeoutSeconds> -n -- <host>` with `LC_ALL=C`; UP/DOWN comes from the exit code (0 reply, 1 no reply or ICMP error, 2 tool error such as an unknown host), the latency from the tool's `time=` field. A process that outlives the timeout is killed. Requires iputils (`ping -V`), present on every mainstream distribution (BusyBox ping is not supported). Do not set `NoNewPrivileges=true` in the unit when running unprivileged — it disables the binary's file capability; the startup probe would report `Operation not permitted`.
- **Shutdown:** `SIGTERM` writes a final heartbeat and checkpoints the WAL. Planned stops longer than `heartbeatGapMinutesThreshold` are still reported as an unexpected offline period on the next start — there is no maintenance mode.

---

## License

Copyright (c) 2026 bayern.kickner.
All rights reserved.
