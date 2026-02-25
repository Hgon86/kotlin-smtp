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
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")

    implementation("io.netty:netty-all")
    implementation("io.netty:netty-codec-haproxy")

    implementation("org.slf4j:slf4j-api")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.4")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    /**
     * Explicitly lock the public API in library mode.
     *
     * - Enforces visibility and return types for public/protected declarations.
     * - Blocks unintended public symbol expansion at build time.
     */
    explicitApi()
}

tasks.withType<Test> {
    useJUnitPlatform()
}
