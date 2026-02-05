plugins {
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
    id("java-library")
    id("io.spring.dependency-management") version "1.1.7"
}

group = "io.github.kotlinsmtp"
version = "0.0.1-SNAPSHOT"

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
    api(project(":kotlin-smtp-core"))

    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.4")

    // For @ConfigurationProperties metadata generation
    compileOnly("org.springframework.boot:spring-boot-configuration-processor")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    // Default host-side implementations (not part of core)
    implementation("dnsjava:dnsjava:3.6.3")
    implementation("org.json:json:20240303")

    // Password hashing (BCrypt)
    implementation("org.springframework.security:spring-security-crypto")

    // Jakarta Mail API + implementation (Angus)
    implementation("jakarta.mail:jakarta.mail-api")
    implementation("org.eclipse.angus:angus-mail:2.0.3")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
