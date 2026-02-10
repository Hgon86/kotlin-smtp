plugins {
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
    id("org.springframework.boot") version "3.4.3"
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
    mavenLocal()  // 로컬 Maven 저장소에서 먼저 검색
    mavenCentral()
}

dependencies {
    // 로컬 Maven 저장소에 배포된 라이브러리 사용 (테스트용)
    implementation("io.github.hgon86:kotlin-smtp-spring-boot-starter:0.1.0")
    implementation("io.github.hgon86:kotlin-smtp-relay-spring-boot-starter:0.1.0")
    // implementation(project(":kotlin-smtp-spring-boot-starter"))  // 프로젝트 직접 참조 시 주석 해제
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
