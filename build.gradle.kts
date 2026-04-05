import info.solidsoft.gradle.pitest.PitestPluginExtension

plugins {
    java
    id("io.quarkus") version "3.8.6"
    jacoco
    id("info.solidsoft.pitest") version "1.15.0"
}

group = "ru.hhassistant"
version = "1.0.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

val quarkusPlatformVersion: String by project
val jooqVersion: String by project
val jsoupVersion: String by project
val telegramBotApiVersion: String by project
val testcontainersVersion: String by project
val okhttpVersion: String by project
val assertjVersion: String by project
val mockitoVersion: String by project

// ─── Source sets ────────────────────────────────────────────────────────────

// Quarkus plugin may register "integrationTest" automatically; fall back to getByName if needed.
val integrationTestSourceSet = sourceSets.findByName("integrationTest")
    ?: sourceSets.create("integrationTest") {
        java.srcDir("src/integrationTest/java")
        resources.srcDir("src/integrationTest/resources")
    }
integrationTestSourceSet.apply {
    compileClasspath += sourceSets.main.get().output + configurations["integrationTestCompileClasspath"]
    runtimeClasspath += sourceSets.main.get().output + configurations["integrationTestRuntimeClasspath"]
}

val contractTestSourceSet = sourceSets.findByName("contractTest")
    ?: sourceSets.create("contractTest") {
        java.srcDir("src/contractTest/java")
        resources.srcDir("src/contractTest/resources")
    }
contractTestSourceSet.apply {
    compileClasspath += sourceSets.main.get().output + configurations["contractTestCompileClasspath"]
    runtimeClasspath += sourceSets.main.get().output + configurations["contractTestRuntimeClasspath"]
}

configurations {
    named("integrationTestImplementation") { extendsFrom(configurations.testImplementation.get()) }
    named("integrationTestRuntimeOnly") { extendsFrom(configurations.testRuntimeOnly.get()) }
    named("contractTestImplementation") { extendsFrom(configurations.testImplementation.get()) }
    named("contractTestRuntimeOnly") { extendsFrom(configurations.testRuntimeOnly.get()) }
}

// ─── Dependencies ────────────────────────────────────────────────────────────

repositories {
    mavenCentral()
}

dependencies {
    // Quarkus BOM
    implementation(enforcedPlatform("${project.properties["quarkusPlatformGroupId"]}:${project.properties["quarkusPlatformArtifactId"]}:${quarkusPlatformVersion}"))
    testImplementation(enforcedPlatform("${project.properties["quarkusPlatformGroupId"]}:${project.properties["quarkusPlatformArtifactId"]}:${quarkusPlatformVersion}"))

    // Core Quarkus
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-resteasy-reactive-jackson")
    implementation("io.quarkus:quarkus-smallrye-health")
    implementation("io.quarkus:quarkus-scheduler")
    implementation("io.quarkus:quarkus-config-yaml")

    // Database
    implementation("io.quarkus:quarkus-jdbc-postgresql")
    implementation("io.quarkus:quarkus-flyway")
    implementation("io.quarkiverse.jooq:quarkus-jooq:2.1.0")
    implementation("org.jooq:jooq:${jooqVersion}")

    // Observability
    implementation("io.quarkus:quarkus-micrometer-registry-prometheus")
    implementation("io.quarkus:quarkus-opentelemetry")
    implementation("io.quarkus:quarkus-logging-json")

    // JSON — Quarkus manages Jackson version via BOM
    implementation("io.quarkus:quarkus-jackson")

    // HTML parsing
    implementation("org.jsoup:jsoup:${jsoupVersion}")

    // Telegram Bot API – thin adapter library, long-polling first
    implementation("com.github.pengrad:java-telegram-bot-api:${telegramBotApiVersion}")

    // HTTP client for hh.ru (authenticated web flows, cookie jar, interceptors)
    implementation("com.squareup.okhttp3:okhttp:${okhttpVersion}")
    implementation("com.squareup.okhttp3:logging-interceptor:${okhttpVersion}")

    // Unit tests
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("org.assertj:assertj-core:${assertjVersion}")
    testImplementation("org.mockito:mockito-core:${mockitoVersion}")
    testImplementation("org.mockito:mockito-junit-jupiter:${mockitoVersion}")

    // Integration tests
    "integrationTestImplementation"("io.quarkus:quarkus-test-common")
    "integrationTestImplementation"("org.testcontainers:postgresql:${testcontainersVersion}")
    "integrationTestImplementation"("org.testcontainers:junit-jupiter:${testcontainersVersion}")
    "integrationTestImplementation"("io.quarkus:quarkus-junit5")

    // Contract tests (mock HTTP server)
    "contractTestImplementation"("com.squareup.okhttp3:mockwebserver:${okhttpVersion}")
    "contractTestImplementation"("org.testcontainers:junit-jupiter:${testcontainersVersion}")
}

// ─── Compile options ─────────────────────────────────────────────────────────
// No --enable-preview: all features used (records, sealed classes, switch expressions)
// are standard in Java 21.

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

// ─── Test tasks ──────────────────────────────────────────────────────────────

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
    jvmArgs("-Djunit.jupiter.testinstance.lifecycle.default=per_class")
}

val integrationTest by tasks.registering(Test::class) {
    description = "Запускает интеграционные тесты с реальным PostgreSQL через Testcontainers"
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
    finalizedBy(tasks.jacocoTestReport)
    // Подставляем фиктивные значения обязательных env-переменных для тестового окружения
    environment("TELEGRAM_BOT_TOKEN", System.getenv("TELEGRAM_BOT_TOKEN") ?: "test_token_placeholder")
}

val contractTest by tasks.registering(Test::class) {
    description = "Запускает контрактные тесты для HH API и Telegram адаптера"
    group = "verification"
    testClassesDirs = sourceSets["contractTest"].output.classesDirs
    classpath = sourceSets["contractTest"].runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
}

val mutationTest by tasks.registering {
    description = "Запускает PIT mutation testing для критичной domain-логики"
    group = "verification"
    dependsOn(tasks.test)
    finalizedBy("pitest")
}

// ─── JaCoCo ──────────────────────────────────────────────────────────────────

jacoco {
    toolVersion = "0.8.12"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    executionData.setFrom(
        fileTree(layout.buildDirectory).include("jacoco/*.exec")
    )
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    violationRules {
        rule {
            limit {
                minimum = "0.90".toBigDecimal()
            }
        }
        rule {
            element = "PACKAGE"
            includes = listOf("ru.hhassistant.domain.*", "ru.hhassistant.application.*")
            limit {
                minimum = "0.95".toBigDecimal()
            }
        }
    }
}

// ─── PIT ─────────────────────────────────────────────────────────────────────

configure<PitestPluginExtension> {
    junit5PluginVersion.set(project.properties["pitestJunit5PluginVersion"].toString())
    targetClasses.set(
        listOf(
            "ru.hhassistant.domain.*",
            "ru.hhassistant.application.*"
        )
    )
    targetTests.set(listOf("ru.hhassistant.*Test"))
    threads.set(4)
    outputFormats.set(listOf("HTML", "XML"))
    mutators.set(listOf("DEFAULTS", "STRONGER"))
    coverageThreshold.set(80)
    mutationThreshold.set(70)
    timeoutFactor.set("1.5".toBigDecimal())
    excludedClasses.set(
        listOf(
            "ru.hhassistant.domain.model.*",
            "ru.hhassistant.adapter.*"
        )
    )
}

// ─── Docker image tasks ───────────────────────────────────────────────────────

val buildJvmImage by tasks.registering(Exec::class) {
    description = "Собирает JVM Docker-образ (основной production-профиль)"
    group = "build"
    dependsOn("quarkusBuild")
    commandLine(
        "docker", "build",
        "-f", "src/main/docker/Dockerfile.jvm",
        "-t", "hh-vacancy-assistant:jvm",
        "--label", "org.opencontainers.image.version=${project.version}",
        "."
    )
}

val buildNativeImage by tasks.registering(Exec::class) {
    description = "Собирает GraalVM Native Docker-образ (опциональный профиль, требует ~20 мин)"
    group = "build"
    commandLine(
        "docker", "build",
        "-f", "src/main/docker/Dockerfile.native",
        "-t", "hh-vacancy-assistant:native",
        "--label", "org.opencontainers.image.version=${project.version}",
        "."
    )
}

// integrationTest и contractTest требуют Docker; запускаются явно или в CI-пайплайне.
// tasks.check содержит только быстрые unit-тесты.
// В CI: ./gradlew test integrationTest contractTest
