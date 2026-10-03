# ADR-0002: Gradle multi-module monorepo, Java 21, Spring Boot 3

- Status: accepted
- Date: 2026-10-03

## Context
The system has several services that share build conventions and are developed by one person. Wise's backend is primarily Java/Kotlin with Spring Boot and Gradle.

## Decision
- One repository, Gradle multi-module build (`services/*`), Kotlin DSL, version catalog in `gradle/libs.versions.toml`.
- Java 21 via Gradle toolchains; Spring Boot 3.3.
- Compiler runs with `-Xlint:all -Werror` so warnings don't accumulate.

## Alternatives
- **Polyrepo (one repo per service):** closer to how large companies split ownership, but adds cross-repo versioning overhead with no benefit for a single developer.
- **Maven:** equally valid; Gradle chosen for faster incremental builds and because Wise uses it.
- **Kotlin:** used at Wise too, but Java is the interview language here.

## Consequences
- One `./gradlew build` builds and tests everything.
- Services still have independent deployables and databases; the monorepo is a development convenience, not a shared runtime.
