# Einwände und Anmerkungen zum Argos-Konzept

Dieses Dokument hält alle Einwände, technischen Unstimmigkeiten und Verbesserungsvorschläge fest, die bei der Analyse und Umsetzung des Konzepts aus [`argos-implementation-plan.md`](./argos-implementation-plan.md) identifiziert wurden.

---

## 1. Abweichungen bei externen Bibliotheken (Klogger)

### Befund
Im Konzept ([`argos-implementation-plan.md`](./argos-implementation-plan.md), Task 6 & Task 11) wird folgendes Logging-Muster vorgegeben:
```kotlin
import klogger.LogLevel
import klogger.staticLog

staticLog(LogLevel.ERROR, "Tag") { "Nachricht" }
```

### Einwand
In der referenzierten Abhängigkeit `bayern.kickner:Klogger:0.1.0` existiert das Package `klogger` nicht als Root-Package und es gibt kein Enum `LogLevel`. Die tatsächliche Signatur in der JAR lautet:
- Package: `bayern.kickner.klogger`
- Enum: `bayern.kickner.klogger.KLogger.Level` (`DEBUG`, `INFO`, `WARN`, `ERROR`, `CRASH`)
- Methode: `staticLog(KLogger.Level, String, () -> String)`

### Umsetzung
Der Code wird mit den korrekten Imports und Enums implementiert:
```kotlin
import bayern.kickner.klogger.KLogger
import bayern.kickner.klogger.staticLog

staticLog(KLogger.Level.ERROR, "Tag") { "Nachricht" }
```
Ohne diese Korrektur wäre das Projekt nicht kompilierbar.

---

## 2. Package-Struktur bei Exposed 1.5.0

### Befund
Das Konzept definiert die Abhängigkeit `org.jetbrains.exposed:exposed-core:1.5.0`, verwendet in den Codebeispielen jedoch die veralteten Imports `org.jetbrains.exposed.sql.*`.

### Einwand
In Version 1.5.0 von Exposed hat JetBrains die Package-Struktur modernisiert:
- Core-Klassen (`Table`, `PrimaryKey`, Spaltentypen): `org.jetbrains.exposed.v1.core.*`
- JDBC- und Transaktions-Funktionen (`Database`, `SchemaUtils`, `transaction`, `insert`, `selectAll`, `deleteWhere`): `org.jetbrains.exposed.v1.jdbc.*`
- Datums-Erweiterungen: `org.jetbrains.exposed.v1.javatime.*`

### Umsetzung
Die Imports und Aufrufe wurden an die tatsächliche Struktur von Exposed 1.5.0 angepasst.

---

## 3. KotNexLib ResultOf2 Typ-Parameter

### Befund
Im Testcode von Task 2 des Konzepts wurde `ResultOf2.Success<AppConfig, ConfigError>` verwendet.

### Einwand
In KotNexLib 4.3.0 ist `ResultOf2.Success<out E>` mit genau einem Typparameter definiert (`ResultOf2<E, Nothing>`), ebenso `ResultOf2.Failure<out Q>`. Zwei Typparameter führen zu einem Kompilierungsfehler.

### Umsetzung
Die Typzusicherungen in den Tests wurden auf `ResultOf2.Success<AppConfig>` bzw. `ResultOf2.Failure<ConfigError>` korrigiert.

---

## 4. Scheduler-Drift und verpasste Prüfungen (`delay(1000)`)

### Befund
Im Konzept (Task 8) prüft der Scheduler fällige Monitore mit:
```kotlin
fun dueMonitors(now: Instant, monitors: List<MonitorConfig>): List<MonitorConfig> =
    monitors.filter { now.epochSecond % it.intervalSeconds == 0L }
```
In der Endlosschleife wird am Ende jedes Durchlaufs ein fixes `delay(1000)` ausgeführt.

### Einwand
Ein fixes `delay(1000)` berücksichtigt nicht die Rechenzeit der Schleife und garantiert keine Ausrichtung an der Systemsekunde. Dadurch akkumuliert sich ein Drift:
- Eine Sekunde kann übersprungen werden (z.B. Ausführung bei `12:00:00.995` und die nächste erst bei `12:00:02.005`).
- Wenn Sekunde `00` eines Minutenintervalls übersprungen wird, wird ein Monitor mit `intervalSeconds = 60` für eine volle Minute überhaupt nicht ausgeführt.
- Auch die tägliche Retention-Bereinigung (`now.epochSecond % 86400 == 0L`) kann so leicht vollständig verpasst werden, da sie nur in exakt einer spezifischen Sekunde pro Tag getriggert wird.

### Umsetzung
- Die Schlafdauer wird dynamisch bis zum Erreichen der nächsten vollen Sekunde berechnet (`1000 - (System.currentTimeMillis() % 1000)`).
- Zur Vermeidung verpasster Zyklen bei temporärem Jitter merkt sich der Scheduler die zuletzt verarbeitete Epoch-Sekunde und arbeitet etwaige Lücken ab.

---

## 5. Ping-Checks (`InetAddress.isReachable`) unter Linux

### Befund
Im Konzept (Task 4) wird der ICMP-Ping folgendermaßen implementiert:
```kotlin
InetAddress.getByName(config.host).isReachable((timeoutSeconds * 1000).toInt())
```
Unter Linux erfordert das Senden von echten ICMP-Echo-Requests über Standard-Java zwingend Root-Rechte (Raw Sockets). Fehlen diese Rechte, fällt die JVM stillschweigend auf einen TCP-Verbindungsversuch zu Port 7 (Echo-Port) zurück.

### Einwand
- Da Port 7 auf praktisch keinem Zielsystem im Internet oder modernen Intranets geöffnet ist, liefert der Ping-Check für unprivilegierte Benutzer fast ausnahmslos `false`.
- Das Konzept löst dies in Task 12 über `User=root` im systemd-Service. Dies widerspricht jedoch dem Best-Practice-Prinzip der minimalen Rechtevergabe (Least Privilege), insbesondere da auf demselben Prozess ein öffentlicher HTTP-Server läuft.

### Empfehlung
- Für Produktion empfiehlt sich entweder die Vergabe von `CAP_NET_RAW` an die Java-Binary/den Prozess (`setcap cap_net_raw+ep ...`) oder das Ausweichen auf den Systemaufruf `/bin/ping -c 1 -W <timeout> <host>`, welcher standardmäßig via SUID/Capabilities ohne Root-Rechte des gesamten Prozesses funktioniert.

---

## 6. Blockierender Mail-Versand im Coroutine-Scheduler

### Befund
In Task 6 wird `Transport.send(message)` synchron aufgerufen:
```kotlin
fun sendMail(config: SmtpConfig, subject: String, body: String) {
    ...
    Transport.send(message)
}
```
Der `NotificationDispatcher` ruft dies in `sendSystemNotification` und `sendMonitorNotification` auf.

### Einwand
Der SMTP-Handshake und Mailversand ist ein blockierender I/O-Vorgang über ein TCP-Netzwerk, der bei Netzwerkproblemen oder Timeouts mehrere Sekunden blockieren kann. Wenn dies auf einem Standard-Coroutine-Kontext ausgeführt wird, kann es Threads blockieren.

### Umsetzung
Aufrufe von `sendMail` werden explizit mit `withContext(Dispatchers.IO)` abgesichert, sodass der Scheduler-Ablauf nicht blockiert wird.

---

## 7. Ktor 3 Client Redirect-Konfiguration

### Befund
In Task 4 (`HttpCheck.kt`) steht:
```kotlin
private val clientWithRedirects = HttpClient(CIO) { followRedirects = true }
private val clientWithoutRedirects = HttpClient(CIO) { followRedirects = false }
```

### Einwand
In Ktor 3 ist die Weiterleitungs-Steuerung standardmäßig aktiv bzw. wird über das `HttpRedirect`-Plugin gesteuert (`followRedirects = false` ist Teil der Engine- bzw. Plugin-Konfiguration).

### Umsetzung
Wir stellen sicher, dass die Ktor-3-spezifische Syntax zur Redirect-Aktivierung und -Deaktivierung fehlerfrei kompiliert und greift.

---

## 8. Projektname `Argos` vs. `Argos`

### Befund
Das Projektverzeichnis lautet `/home/marvin/IdeaProjects/Argos`, und die vorhandene `settings.gradle.kts` deklarierte ursprünglich `rootProject.name = "Argos"`.

### Einwand
Das Konzept spezifiziert durchgängig:
- `rootProject.name = "Argos"`
- Artefakt: `argos.jar`
- Paket: `bayern.kickner.argos`

### Umsetzung
Wir folgen strikt dem Konzept und setzen `Argos` als Projekt- und Artefaktname.

---

## 9. Status-Page Datenanbindung

### Befund
In Task 9 rendert `respondStatusPage(page)` lediglich eine ungeordnete HTML-Liste der Monitor-IDs:
```kotlin
ul { page.monitorIds.forEach { li { +it } } }
```

### Einwand
Eine Status-Page sollte dem Endbenutzer den Zustand (UP / DOWN) und idealerweise die letzte Latenz anzeigen. Da `configureWeb` im Konzept nur `config` übergeben bekommt, fehlen die aktuellen Prüfdaten.

### Umsetzung
Die Basisfunktion wird gemäß Konzept implementiert. Um Erweiterbarkeit zu gewährleisten, kann die Datenbank oder der letzte Zustand bei Bedarf durchgereicht werden.

---

## 10. Vollständiger Verzicht auf Netty zugunsten der Ktor CIO-Engine

### Befund
Im ursprünglichen Konzept wurde `io.ktor:ktor-server-netty:3.5.2` als HTTP-Server-Engine spezifiziert, während für den HTTP-Client bereits die CIO-Engine (`io.ktor:ktor-client-cio:3.5.2`) verwendet wurde.

### Einwand
- Netty zieht einen umfangreichen Baum an Abhängigkeiten und nativen Bibliotheken (JNI-Transports für epoll/kqueue, BlockHound etc.) nach sich, was die Fat-Jar-Größe um ca. 5.8 MB erhöht (von ~36 MB auf ~41.8 MB).
- Die Ktor CIO-Engine (Coroutine I/O) ist vollständig in Kotlin geschrieben und integriert sich nahtlos in das strukturierte Concurrency-Modell von Kotlin Coroutines, ohne separate Thread-Pool-Hierarchien wie in Netty zu benötigen.
- Durch den Verzicht auf Netty wird eine einheitliche I/O-Engine für Server und Client geschaffen.

### Umsetzung
- Server-Abhängigkeit auf `io.ktor:ktor-server-cio:3.5.2` umgestellt.
- Server-Boot in `Main.kt` verwendet `embeddedServer(ServerCIO, port = 8080)`.
- Das Fat-JAR `argos.jar` ist dadurch schlanker, startet schneller und vermeidet native Abhängigkeiten.

