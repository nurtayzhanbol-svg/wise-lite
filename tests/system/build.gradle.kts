// End-to-end system test: real boot jars as separate OS processes, real Postgres + Kafka (Testcontainers),
// fault injection + process kills. Slow, so not part of `build`; run with `./gradlew systemTest`.
plugins {
    java
    alias(libs.plugins.spring.dependency.management)
}

dependencyManagement {
    imports { mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}") }
}

// Boot 3.3 BOM pins an older Testcontainers that Docker 29 rejects (same pin as the service modules).
extra["testcontainers.version"] = "1.21.4"

val services = listOf("transfer-service", "payout-worker", "rails-simulator", "reconciliation-job", "risk-engine")
services.forEach { evaluationDependsOn(":services:$it") }

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.awaitility:awaitility")
    testImplementation("com.fasterxml.jackson.core:jackson-databind")
    testImplementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:kafka")
    testImplementation("org.apache.kafka:kafka-clients")
    testRuntimeOnly("org.postgresql:postgresql")
    testRuntimeOnly("org.slf4j:slf4j-simple")
}

tasks.test { enabled = false }

val systemTest by tasks.registering(Test::class) {
    description = "Runs the whole system under load with injected faults."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    outputs.upToDateWhen { false }
    testLogging { showStandardStreams = true }
    services.forEach { name ->
        val bootJar = project(":services:$name").tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar")
        dependsOn(bootJar)
        systemProperty("jar.$name", bootJar.get().archiveFile.get().asFile.absolutePath)
    }
}
