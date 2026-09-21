plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    id("io.ktor.plugin") version "3.5.2"
    application
}

group = "bayern.kickner"
version = "0.2.0"

repositories {
    mavenCentral()
    maven {
        name = "nexus421MavenReleases"
        url = uri("https://maven.kickner.bayern/releases")
    }
}

dependencies {
    implementation("bayern.kickner:Klogger:0.1.0")
    implementation("bayern.kickner:KotNexLib:4.4.1")

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

    // SLF4J provider so Ktor/Hikari/Exposed warnings reach the journal (level set in Main.kt)
    runtimeOnly("org.slf4j:slf4j-simple:2.0.18")

    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    testImplementation("io.ktor:ktor-server-test-host:3.5.2")
    testImplementation("io.ktor:ktor-client-mock:3.5.2")
    // Runs the setup page's config-model.js inside Kotest so browser and server validation are checked against each other
    testImplementation("org.graalvm.polyglot:polyglot:25.3.4.1")
    testImplementation("org.graalvm.polyglot:js:25.3.4.1")
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
    // Same zone as production (Main.kt): Exposed writes SQLite timestamps in the JVM default zone
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Duser.timezone=UTC")
}

ktor {
    fatJar {
        archiveFileName.set("argos.jar")
    }
}