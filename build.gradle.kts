plugins {
    kotlin("jvm") version "1.9.25"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.1"
    // 새 Central Portal 지원 플러그인
    id("com.vanniktech.maven.publish") version "0.29.0" apply false
}

val publishableModules = setOf(
    "kotlin-smtp-core",
    "kotlin-smtp-relay",
    "kotlin-smtp-relay-jakarta-mail",
    "kotlin-smtp-relay-spring-boot-starter",
    "kotlin-smtp-spring-boot-starter",
)

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
            "kotlin-smtp-example-app",
            "kotlin-smtp-spring-boot-starter",
            "kotlin-smtp-relay-spring-boot-starter",
            "kotlin-smtp-relay-jakarta-mail",
        )
    )
}

subprojects {
    if (name in publishableModules) {
        apply(plugin = "com.vanniktech.maven.publish")

        plugins.withId("java") {
            extensions.configure<JavaPluginExtension> {
                withSourcesJar()
                withJavadocJar()
            }
        }

        // Maven Central Portal 설정
        extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            // Central Portal 자동 게시(배포 후 자동 publish)
            publishToMavenCentral(
                com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL,
                automaticRelease = true,
            )

            signAllPublications()

            coordinates(group.toString(), project.name, version.toString())

            pom {
                name.set(project.name)
                description.set("Kotlin SMTP server libraries and Spring Boot starters")
                inceptionYear.set("2024")
                url.set("https://github.com/Hgon86/kotlin-smtp")

                licenses {
                    license {
                        name.set("Apache License 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }

                developers {
                    developer {
                        id.set("hgon86")
                        name.set("Hgon86")
                        email.set("hgon86@users.noreply.github.com")
                    }
                }

                scm {
                    connection.set("scm:git:https://github.com/Hgon86/kotlin-smtp.git")
                    developerConnection.set("scm:git:ssh://git@github.com/Hgon86/kotlin-smtp.git")
                    url.set("https://github.com/Hgon86/kotlin-smtp")
                }
            }
        }
    }
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

// 새 Central Portal 인증 설정
// GitHub Secrets 필요:
// - MAVEN_CENTRAL_USERNAME: central.sonatype.com User Token Username
// - MAVEN_CENTRAL_PASSWORD: central.sonatype.com User Token Password
// - SIGNING_KEY: GPG private key
// - SIGNING_PASSWORD: GPG passphrase
