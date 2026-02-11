plugins {
    kotlin("jvm") version "1.9.25"
    id("java-library")
    id("io.spring.dependency-management") version "1.1.7"
}

group = "io.github.hgon86"
version = "0.1.2"

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
     * relay 모듈은 다른 구현(dnsjava/jakarta-mail 등)에 의존하지 않는 "API 경계"이므로,
     * 공개 API가 의도치 않게 확장되는 것을 빌드 타임에 차단합니다.
     */
    explicitApi()
}

tasks.withType<Test> {
    useJUnitPlatform()
}
