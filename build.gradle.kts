plugins {
    kotlin("jvm") version "1.9.25"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.1"
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
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
        apply(plugin = "maven-publish")
        apply(plugin = "signing")

        plugins.withId("java") {
            extensions.configure<JavaPluginExtension> {
                withSourcesJar()
                withJavadocJar()
            }

            extensions.configure<org.gradle.api.publish.PublishingExtension> {
                publications {
                    create<org.gradle.api.publish.maven.MavenPublication>("mavenJava") {
                        from(components["java"])
                        groupId = project.group.toString()
                        artifactId = project.name
                        version = project.version.toString()

                        pom {
                            name.set(project.name)
                            description.set("Kotlin SMTP server libraries and Spring Boot starters")
                            url.set("https://github.com/Hgon86/kotlin-smtp")

                            licenses {
                                license {
                                    name.set("Apache License 2.0")
                                    url.set("https://www.apache.org/licenses/LICENSE-2.0")
                                }
                            }

                            scm {
                                connection.set("scm:git:https://github.com/Hgon86/kotlin-smtp.git")
                                developerConnection.set("scm:git:ssh://git@github.com/Hgon86/kotlin-smtp.git")
                                url.set("https://github.com/Hgon86/kotlin-smtp")
                            }

                            developers {
                                developer {
                                    id.set("hgon86")
                                    name.set("Hgon86")
                                    email.set("hgon86@users.noreply.github.com")
                                }
                            }
                        }
                    }
                }
                repositories {
                    mavenLocal()

                    val ossrhUsername = providers.gradleProperty("ossrhUsername")
                        .orElse(providers.environmentVariable("OSSRH_USERNAME"))
                    val ossrhPassword = providers.gradleProperty("ossrhPassword")
                        .orElse(providers.environmentVariable("OSSRH_PASSWORD"))

                    if (ossrhUsername.isPresent && ossrhPassword.isPresent) {
                        maven {
                            name = "OSSRH"
                            val isSnapshot = project.version.toString().endsWith("SNAPSHOT")
                            url = uri(
                                if (isSnapshot) {
                                    "https://s01.oss.sonatype.org/content/repositories/snapshots/"
                                } else {
                                    "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/"
                                }
                            )
                            credentials {
                                username = ossrhUsername.get()
                                password = ossrhPassword.get()
                            }
                        }
                    }
                }
            }

            extensions.configure<org.gradle.plugins.signing.SigningExtension> {
                val signingKey = providers.gradleProperty("signingKey")
                    .orElse(providers.environmentVariable("SIGNING_KEY"))
                    .orNull
                val signingPassword = providers.gradleProperty("signingPassword")
                    .orElse(providers.environmentVariable("SIGNING_PASSWORD"))
                    .orNull
                val isSnapshot = project.version.toString().endsWith("SNAPSHOT")

                if (!signingKey.isNullOrBlank()) {
                    useInMemoryPgpKeys(signingKey, signingPassword)
                    sign(extensions.getByType<org.gradle.api.publish.PublishingExtension>().publications)
                }

                setRequired {
                    gradle.taskGraph.allTasks.any { task -> task.name.startsWith("publish") } && !isSnapshot
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

nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://s01.oss.sonatype.org/service/local/"))
            snapshotRepositoryUrl.set(uri("https://s01.oss.sonatype.org/content/repositories/snapshots/"))
            username.set(
                providers.gradleProperty("ossrhUsername")
                    .orElse(providers.environmentVariable("OSSRH_USERNAME"))
                    .orNull
            )
            password.set(
                providers.gradleProperty("ossrhPassword")
                    .orElse(providers.environmentVariable("OSSRH_PASSWORD"))
                    .orNull
            )
        }
    }
}
