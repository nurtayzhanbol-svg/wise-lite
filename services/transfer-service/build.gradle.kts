plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

// Testcontainers < 1.21.4 defaults to Docker API 1.32, which Docker Engine 29+ rejects.
extra["testcontainers.version"] = "1.21.4"

dependencies {
    implementation(project(":libs:events"))
    implementation(project(":libs:outbox"))
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:kafka")
    testImplementation("org.awaitility:awaitility")
}

dependencies {
    testImplementation("net.jqwik:jqwik:1.9.1")
}

// Benchmarks are slow and machine-dependent: excluded from `test`, run with `./gradlew benchmark`.
tasks.test {
    useJUnitPlatform { excludeTags("benchmark") }
}

tasks.register<Test>("benchmark") {
    description = "Runs locking-strategy benchmarks (tagged 'benchmark')."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("benchmark") }
    outputs.upToDateWhen { false }
    testLogging { showStandardStreams = true }
}
