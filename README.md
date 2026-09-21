# Argos

[![Tests](https://github.com/nexus421/Argos/actions/workflows/test.yml/badge.svg)](https://github.com/nexus421/Argos/actions/workflows/test.yml)
![Kotlin](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fraw.githubusercontent.com%2Fnexus421%2FArgos%2Fmaster%2Fbuild.gradle.kts&search=kotlin%5C%28%22jvm%22%5C%29%20version%20%22%28%5B%5E%22%5D%2B%29%22&replace=%241&label=Kotlin&logo=kotlin&logoColor=white&color=7F52FF)
![Ktor](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fraw.githubusercontent.com%2Fnexus421%2FArgos%2Fmaster%2Fbuild.gradle.kts&search=id%5C%28%22io%5C.ktor%5C.plugin%22%5C%29%20version%20%22%28%5B%5E%22%5D%2B%29%22&replace=%241&label=Ktor&logo=ktor&logoColor=white&color=087CFA)
![JDK](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fraw.githubusercontent.com%2Fnexus421%2FArgos%2Fmaster%2Fbuild.gradle.kts&search=JavaLanguageVersion%5C.of%5C%28%28%5Cd%2B%29%5C%29&replace=%241%20%28Corretto%29&label=JDK&logo=openjdk&logoColor=white&color=ED8B00)
[![Release](https://img.shields.io/github/v/release/nexus421/Argos)](https://github.com/nexus421/Argos/releases)

**Argos**: a lightweight, self-hosted JVM infrastructure and service monitoring system distributed as a single executable Fat JAR. Argos runs scheduled multi-protocol health checks (HTTP, TCP, Ping, DNS), measures sub-millisecond latencies, maintains 30-day uptime history in SQLite, dispatches alerts via SMTP and webhooks, and serves lightweight server-side rendered status pages.

KISS by design: no Docker required, no login or mutation API, no runtime configuration. One JSON config file, one fat JAR, one systemd service. TLS termination and rate limiting are left to a reverse proxy. Even with a missing or invalid configuration, Argos starts in bootstrap mode (serving an interactive client-side setup helper at `/setup` and HTTP 503 on `/`), preventing crash loops while alerting external monitors.

## Quick start

Requirements: JDK 25 to build (Amazon Corretto is the pinned toolchain; Gradle downloads it if missing) and a Java 25 runtime on Linux for `argos.jar`.

```bash
cp config.example.json config.json   # or start without config to use the web builder at /setup
./gradlew run                        # development: reads ./config.json
./gradlew build                      # production: build/libs/argos.jar
java -jar build/libs/argos.jar configPath=/path/to/config.json
```

With the server running, check health:

```bash
curl -i http://127.0.0.1:8080/
```

Tests: `./gradlew test` — GitHub Actions runs them on every push (`.github/workflows/test.yml`).

## Command line

| Argument | Default | Description |
|---|---|---|
| `configPath=<path>` | `config.json` | Path to JSON config file (see Configuration). |

Modes and exit codes:

| Mode / Code       | Meaning                                                                                                                                                                        |
|-------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `0`               | Normal operation (monitoring active, `/` returns `200 ok`).                                                                                                                    |
| Bootstrap (`503`) | Missing/invalid config or inaccessible `dataDir`. Argos stays alive and serves `/setup` while `/` returns `503 Service Unavailable` with problem category. Details in journal. |
| `143`             | JVM exit code after SIGTERM (`systemctl stop`). Clean stop: checks cancelled, up to 30 s to drain pending alert deliveries, final heartbeat written, WAL checkpointed.         |

## Configuration

One JSON file, `config.json` in the working directory by default (`configPath=<path>` overrides). Read once at startup; restart after changes. Strict validation: unknown fields, malformed values, or invalid references reject the config with an issue list in the journal and switch Argos to bootstrap mode. Passwords and webhook tokens are never logged.

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
        "password": "change-me"
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

#### Global settings

| Field | Type | Default | Description |
|---|---|---|---|
| `monitors` | List\<Monitor\> | `[]` | List of monitors to execute. |
| `smtpChannels` | List\<SmtpChannel\> | `[]` | Email notification targets. |
| `webhookChannels` | List\<WebhookChannel\> | `[]` | HTTP webhook notification targets. |
| `statusPages` | List\<StatusPage\> | `[]` | Status page definitions. |
| `retentionDays` | Int | `365` | Days to keep check history in SQLite. Old records purged daily at 00:00 UTC. |
| `flappingThreshold` | Int | `3` | Consecutive failures required before firing a DOWN alert. |
| `heartbeatGapMinutesThreshold` | Int | `2` | Minimum gap between heartbeats reported as downtime or pause. |
| `dataDir` | String | `"./data"` | Directory for SQLite database (`argos.db`). Created if missing. |
| `webHost` | String | `"0.0.0.0"` | Bind interface for Ktor web server. |
| `webPort` | Int | `8080` | Port for Ktor web server. |

#### `monitors[]`

| Field | Type | Default | Description |
|---|---|---|---|
| `id` | String | required | `[A-Za-z0-9_.-]`, 1–64 chars, unique. History key (renaming orphans history). |
| `name` | String | required | Display name on status pages and in alerts. |
| `intervalSeconds` | Int | required | Execution interval (> 0). Aligned to the epoch grid. |
| `timeoutSeconds` | Int | required | Timeout (> 0 and < `intervalSeconds`). Covers connection and DNS resolution. |
| `check` | Object | required | Check configuration (`http`, `tcp`, `ping`, or `dns`). |
| `notificationChannelIds` | List\<String\>? | `null` | Target channel IDs. `null` = all channels; `[]` = status-page only. |

#### `check` types

| Type | Fields | Description |
|---|---|---|
| `http` | `url` (required), `method` (`GET`), `headers` (`{}`), `expectedStatusCodes` (`[200]`), `bodyRegex` (optional), `followRedirects` (`true`) | Custom HTTP request. `bodyRegex` matches first 1 MiB. Redirects followed for GET/HEAD only. |
| `tcp` | `host` (required), `port` (required, 1–65535) | TCP socket connection check. |
| `ping` | `host` (required) | ICMP echo via system `/usr/bin/ping` (iputils). Reports real RTT. |
| `dns` | `hostname` (required), `expectedIp` (optional) | DNS resolution. If `expectedIp` is given, checks resolved address. |

#### Notification channels

- **`smtpChannels[]`**: `id`, `host`, `port` (e.g. 587/465), `username`, `password`, `from`, `to` (List), `tls` (`"starttls"`, `"ssl"`, `"none"`), `systemEvents` (boolean, default `true`).
- **`webhookChannels[]`**: `id`, `url`, `method` (`POST`), `headers` (`{}`), `bodyTemplate`, `systemEvents` (boolean, default `true`). Placeholders: `{{status}}` (`DOWN`/`UP`/`SYSTEM`), `{{subject}}`, `{{body}}`, `{{monitorId}}`, `{{monitorName}}`. JSON payloads are automatically escaped when `Content-Type: application/json` is set.

#### `statusPages[]`

| Field        | Type           | Default  | Description                                                                                                                                                                                                                                                                                         |
|--------------|----------------|----------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `id`         | String         | required | Path component (`/status/<id>`). `[A-Za-z0-9_.-]`, 1–64 chars, unique.                                                                                                                                                                                                                              |
| `name`       | String         | required | Heading displayed on status page.                                                                                                                                                                                                                                                                   |
| `monitorIds` | List\<String\> | required | IDs of monitors displayed on this page.                                                                                                                                                                                                                                                             |
| `basicAuth`  | Object?        | `null`   | Optional `{ "username": "...", "password": "..." }`. Plain text, like the SMTP passwords — keep `config.json` at `chmod 600`. Protected pages show internal error logs. Up to 0.1.0 this field was `passwordHash` (Argon2); such configs are rejected at start with `Field 'password' is required`. |

## Web endpoints

### `GET /`

Health check endpoint:
- **`200 ok`**: Valid configuration loaded, monitoring active.
- **`503 Service Unavailable`**: Bootstrap mode. Returned when config is missing, invalid, or `dataDir` cannot be opened. Response body names problem category; details in journal.

### `GET /setup`

Interactive browser-based configuration editor:
- Entirely client-side: never transmits files to the server.
- Live validation mirroring server rules (IDs, ranges, URL schemes, email formats, regexes).
- Download or copy generated `config.json`.

### `GET /status/{id}`

Server-side rendered HTML status page (`kotlinx.html`):
- Monitor statuses (UP / DOWN / UNKNOWN), last latency, check timestamp (`dd.MM.yyyy HH:mm:ss UTC`).
- 30-day SVG history bar chart: uptime percentage, check counts, daily latency. Cached 60 s per monitor.
- Auto-refreshes every 30 s; light and dark theme.
- Protected pages (via `basicAuth`) display internal error details; public pages hide them.

## Behaviour

**Scheduling & Checks.** Monitors run on an epoch-aligned grid (multiples of `intervalSeconds`). Checks for a single monitor never overlap — if a check is still in flight, the next tick is skipped. Missed checks during pauses are not caught up. Latencies are measured with `System.nanoTime()` with sub-millisecond precision. ICMP checks call `/usr/bin/ping` (iputils) unprivileged; if missing, ping checks fail.

**Alert delivery & Resilience.** Check results and triggered alerts are committed in a single atomic SQLite transaction (`pending_alert`). Flapping protection triggers DOWN after `flappingThreshold` consecutive failures; recovery notices include outage duration. Notifications dispatch across channels in parallel with four retry attempts (10/30/60 s pauses). Incomplete deliveries remain in SQLite and retry every 5 minutes and on restart; alerts older than 24 h are pruned.

**Self-monitoring & Heartbeat.** A heartbeat is written to SQLite every 30 s, at startup, and during clean shutdown. Downtime exceeding `heartbeatGapMinutesThreshold` is reported as an unexpected offline period on the next start. Runtime scheduler pauses (VM suspend, clock jumps) trigger pause alerts. System events go to channels with `systemEvents: true`.

**Storage & Concurrency.** SQLite operates in WAL mode with 5 s busy timeout and `synchronous=NORMAL`. Writes are serialized via `Dispatchers.IO.limitedParallelism(1)`, while reads use a separate bounded dispatcher. Database schema version is validated at startup and automatically migrated. The JVM is pinned to UTC.

**Logging.** Application logs to stdout via Klogger; SLF4J warnings (Ktor, HikariCP, Exposed) forward to stdout/journal. Timestamps are formatted in UTC. Passwords, auth hashes, and webhook tokens are never logged.

## Deployment

### Quick install (Debian/Ubuntu)

[`scripts/install.sh`](scripts/install.sh) installs Argos as a systemd service and downloads `argos.jar` from the latest GitHub release:

```bash
sudo mkdir -p /opt/argos && cd /opt/argos && curl -fsSL https://raw.githubusercontent.com/nexus421/Argos/master/scripts/install.sh | sudo bash
```

Running the command in the same directory updates Argos while preserving `config.json` and `data/`.

### Manual systemd service

Create `/etc/systemd/system/argos.service`:

```ini
[Unit]
Description=Argos Monitoring
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=argos
WorkingDirectory=/opt/argos
ExecStart=/usr/bin/java --enable-native-access=ALL-UNNAMED -Xmx256m -jar /opt/argos/argos.jar configPath=/opt/argos/config.json
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

Enable and start:

```bash
sudo systemctl daemon-reload && sudo systemctl enable --now argos.service
```

- **Unprivileged execution**: `/usr/bin/ping` uses the `cap_net_raw` file capability; root is not required. Do not set `NoNewPrivileges=true` as it disables this capability.
- **Reverse proxy**: Terminate TLS at a reverse proxy (e.g. Caddy, Zoraxy, nginx). Apply rate limiting to `/status/*` if
  you want to slow down password guessing.
- **Backups**: Backup `<dataDir>/argos.db` while running using `sqlite3 argos.db ".backup argos-backup.db"`.

## Not in scope (deliberately)

No login/session authentication or mutation API, no runtime configuration hot-reload, no Docker target, no TLS in the web server, and no scheduler tick replay or jitter.
