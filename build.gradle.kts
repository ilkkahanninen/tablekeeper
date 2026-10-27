plugins {
    kotlin("jvm") version "2.1.21"
    application
}

group = "tablekeeper"
version = "0.1.0"

repositories {
    mavenCentral()
}

val ktorVersion = "3.1.3"
val jacksonVersion = "2.18.2"

dependencies {
    // Web
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktorVersion")

    // Templates (screen + print share one engine)
    implementation("io.pebbletemplates:pebble:3.2.2")

    // Database
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("org.jooq:jooq:3.19.15")
    implementation("org.flywaydb:flyway-core:10.20.1")
    implementation("org.flywaydb:flyway-database-postgresql:10.20.1")

    // JSON + game-module schemas
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
    implementation("com.networknt:json-schema-validator:1.5.1")

    // Print: HTML -> PDF in headless Chromium (downloaded on first use; the Docker image has it installed)
    implementation("com.microsoft.playwright:playwright:1.63.0")
    implementation("org.apache.pdfbox:pdfbox:2.0.24")  // multi-column imposition

    implementation("ch.qos.logback:logback-classic:1.5.12")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("tablekeeper.AppKt")
}

// Chromium for PDFs (Chromium.kt), headless shell only; quick when it's already there. `run` and the smoke tests need it.
val installChromium by tasks.registering(JavaExec::class) {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.microsoft.playwright.CLI")
    args("install", "--only-shell", "chromium")
}
tasks.named("run") { dependsOn(installChromium) }

// Smoke tests (src/smokeTest): the whole app, routes and SQL, against Postgres in Docker (Testcontainers)
testing {
    suites {
        register<JvmTestSuite>("smokeTest") {
            useJUnitJupiter()
            dependencies {
                implementation(project())
                implementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
                implementation("org.testcontainers:testcontainers-postgresql:2.0.5")
                implementation("org.jetbrains.kotlin:kotlin-test-junit5")
            }
            targets.all {
                testTask.configure {
                    systemProperty("modulesDir", rootProject.file("modules").absolutePath)
                    systemProperty("exportsDir", rootProject.file("src/test/resources/exports").absolutePath)
                    shouldRunAfter(tasks.test)
                    dependsOn(installChromium)
                }
            }
        }
    }
}
// The suite uses the app's own libraries (jOOQ, Jackson, Ktor) directly
configurations["smokeTestImplementation"].extendsFrom(configurations.implementation.get())
tasks.check { dependsOn(testing.suites.named("smokeTest")) }

tasks.test {
    useJUnitPlatform()
    // Tests read the real module definitions from ./modules
    systemProperty("modulesDir", rootProject.file("modules").absolutePath)
    // Saved exports of every file version, read back by ExportCompatibilityTest
    systemProperty("exportsDir", rootProject.file("src/test/resources/exports").absolutePath)
}
