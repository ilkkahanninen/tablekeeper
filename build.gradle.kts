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

    // Print: HTML -> PDF (pure JVM, no browser needed)
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("com.openhtmltopdf:openhtmltopdf-pdfbox:1.0.10")
    implementation("com.openhtmltopdf:openhtmltopdf-svg-support:1.0.10")  // inline <svg>, e.g. the campaign map

    implementation("ch.qos.logback:logback-classic:1.5.12")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("tablekeeper.AppKt")
}

tasks.test {
    useJUnitPlatform()
    // Tests read the real module definitions from ./modules
    systemProperty("modulesDir", rootProject.file("modules").absolutePath)
}
