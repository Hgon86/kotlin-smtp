plugins {
    kotlin("jvm") version "1.9.25"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "io.github.hgon86"
version = "0.1.0"

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
}

kotlin {
    /**
     * 라이브러리 모드에서 공개 API를 명시적으로 고정합니다.
     *
     * - public/protected 선언의 visibility 및 반환 타입을 강제합니다.
     * - 의도치 않은 공개 심볼 확장을 빌드 타임에 차단합니다.
     */
    explicitApi()
}

tasks.withType<Test> {
    useJUnitPlatform()
}
