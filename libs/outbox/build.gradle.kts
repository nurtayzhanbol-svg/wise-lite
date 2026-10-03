plugins {
    `java-library`
    alias(libs.plugins.spring.dependency.management)
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}")
    }
}

dependencies {
    api("org.springframework.kafka:spring-kafka")
    api("org.springframework:spring-jdbc")
    api("org.springframework.boot:spring-boot-autoconfigure")
    api("com.fasterxml.jackson.core:jackson-databind")
    api(project(":libs:events"))
    implementation("org.slf4j:slf4j-api")
}
