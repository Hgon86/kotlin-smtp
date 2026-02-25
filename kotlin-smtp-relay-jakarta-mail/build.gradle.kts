plugins {
    kotlin("jvm") version "1.9.25"
    id("java-library")
    id("io.spring.dependency-management") version "1.1.7"
}

group = "io.github.hgon86"
version = "0.1.5"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.4.3")
    }
}

dependencies {
    api(project(":kotlin-smtp-relay"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.4")

    implementation("dnsjava:dnsjava:3.6.3")
    implementation("jakarta.mail:jakarta.mail-api")
    implementation("org.eclipse.angus:angus-mail:2.0.3")
    implementation("jakarta.activation:jakarta.activation-api")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
