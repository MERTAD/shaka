plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
    id("io.ktor.plugin") version "2.3.7"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    application
}

group = "com.shaka"
version = "1.0.0"

/**
 * Local, never-committed configuration, read from `.env.local` in this module.
 *
 * The file is covered by the `*.local` rule in the root .gitignore, so
 * credentials can sit on disk without ever reaching a commit. Do not move them
 * into a tracked file: this fork is a PUBLIC repository, and a secret in a
 * commit is public the moment it is pushed and stays reachable by SHA
 * afterwards, including after the file is edited or the branch is deleted.
 *
 * Deliberately not applied to the test task. Several tests opt into live
 * network access by checking whether these variables are present, so seeding
 * them here would silently turn the suite from hermetic into a live-integration
 * run for anyone who has this file. Set them in your shell to run those
 * deliberately.
 */
val localEnv: Map<String, String> = run {
    val file = file(".env.local")
    if (!file.exists()) {
        emptyMap()
    } else {
        file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
            .associate { line ->
                val key = line.substringBefore('=').trim()
                val value = line.substringAfter('=').trim().trim('"').trim('\'')
                key to value
            }
    }
}

application {
    mainClass.set("com.shaka.ApplicationKt")
}

tasks.named<JavaExec>("run") {
    // Copied in only when the shell has not already set them, so an explicit
    // `$env:COP_USER=...` for one run still wins over the file.
    localEnv.forEach { (key, value) ->
        if (System.getenv(key) == null) environment(key, value)
    }
}

/**
 * Reports which local keys were found, by name only.
 *
 * Values are never printed. "Is the credential wired up?" is a question whose
 * answer does not require reading the secret back to the terminal, and a
 * diagnostic that echoes secrets is a diagnostic that ends up in a pasted log.
 */
tasks.register("localEnvKeys") {
    group = "help"
    description = "List the key names loaded from .env.local, without their values."
    val keys = localEnv.keys.sorted()
    val loaded = file(".env.local").exists()
    doLast {
        if (!loaded) {
            println(".env.local not found; nothing is being injected.")
        } else if (keys.isEmpty()) {
            println(".env.local has no usable KEY=value lines.")
        } else {
            println(".env.local loaded ${keys.size} key(s): ${keys.joinToString(", ")}")
        }
    }
}

repositories {
    mavenCentral()
}

val ktorVersion = "2.3.7"
val kotlinVersion = "1.9.22"
val logbackVersion = "1.4.14"
val sentryVersion = "8.16.0"

dependencies {
    // Ktor Server
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-compression:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")

    // Ktor Client (for external APIs)
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-client-logging:$ktorVersion")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Logging
    implementation("ch.qos.logback:logback-classic:$logbackVersion")

    // Monitoring — Sentry error tracking
    implementation("io.sentry:sentry:$sentryVersion")
    implementation("io.sentry:sentry-logback:$sentryVersion")

    // Structured JSON logging for Better Stack log drain
    implementation("net.logstash.logback:logstash-logback-encoder:8.1")

    // Cache
    implementation("io.lettuce:lettuce-core:6.3.1.RELEASE")

    // Database
    implementation("org.postgresql:postgresql:42.7.1")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.jetbrains.exposed:exposed-core:0.46.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.46.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.46.0")
    implementation("org.jetbrains.exposed:exposed-java-time:0.46.0")

    // HTML Parsing for web scraping
    implementation("org.jsoup:jsoup:1.17.2")

    // Testing
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-test:$kotlinVersion")
}

kotlin {
    jvmToolchain(17)
}

tasks.shadowJar {
    archiveBaseName.set("shaka-api")
    archiveClassifier.set("all")
    archiveVersion.set("")
    manifest {
        attributes["Main-Class"] = "com.shaka.ApplicationKt"
    }
}

ktor {
    fatJar {
        archiveFileName.set("shaka-api-all.jar")
    }
}
