plugins {
    kotlin("jvm") version "1.9.25"
    id("io.spring.dependency-management") version "1.1.7"
    id("me.champeau.jmh") version "0.7.3"
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
    implementation(project(":kotlin-smtp-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")

    jmh(project(":kotlin-smtp-core"))
    jmh("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    jmh("org.openjdk.jmh:jmh-core:1.37")
    jmhAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
    jmh("org.slf4j:slf4j-simple")

    testImplementation(project(":kotlin-smtp-core"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.register<Test>("performanceTest") {
    description = "Runs end-to-end SMTP performance profile tests"
    group = "verification"
    useJUnitPlatform {
        includeTags("performance")
    }
    systemProperty("kotlinsmtp.performance.enabled", "true")
    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "error")
}
