plugins {
    kotlin("jvm") version "1.9.25"
    id("java-library")
    id("io.spring.dependency-management") version "1.1.7"
}

group = "io.github.hgon86"
version = "0.1.3"

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

    // API-only module: keep dependencies minimal.
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    /**
     * The relay module is an "API boundary" that does not depend on other implementations (dnsjava/jakarta-mail, etc.),
     * so we block unintended public API expansion at build time.
     */
    explicitApi()
}

tasks.withType<Test> {
    useJUnitPlatform()
}
