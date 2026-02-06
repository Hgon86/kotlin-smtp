plugins {
    kotlin("jvm") version "1.9.25"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.1"
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
    // Root project acts as a test harness / docs holder.
    testImplementation(project(":kotlin-smtp-core"))
    testImplementation("io.netty:netty-handler")
    testImplementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    testImplementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

apiValidation {
    // 현재 단계에서는 core/relay "API 경계"만 고정하고, Spring wiring/impl 모듈은 제외합니다.
    ignoredProjects.addAll(
        listOf(
            // root project는 라이브러리 모듈이 아니라 테스트 harness/docs 용도이므로 API 검증에서 제외합니다.
            "kotlin-smtp",
            "kotlin-smtp-spring-boot-starter",
            "kotlin-smtp-relay-spring-boot-starter",
            "kotlin-smtp-relay-jakarta-mail",
        )
    )
}

// Gradle 8.x에서는 같은 빌드에서 apiDump + apiCheck를 함께 실행할 때
// task output 의존성(암묵적 의존) 검증이 실패할 수 있습니다.
// 개발자가 수동으로 `apiDump` 후 `apiCheck`를 연달아 실행할 때도 안전하도록,
// 동일 프로젝트 내에서 실행 순서만 보장합니다.
allprojects {
    tasks.matching { it.name == "apiCheck" }.configureEach {
        mustRunAfter("apiDump")
    }
}
