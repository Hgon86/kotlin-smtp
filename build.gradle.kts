plugins {
    kotlin("jvm") version "1.9.25"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.1"
    jacoco
    // New Central Portal support plugin
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
version = "0.1.4"

allprojects {
    // Enforce Central Portal namespace validation and consistent publishing coordinates.
    group = "io.github.hgon86"
    version = "0.1.4"
}

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

jacoco {
    toolVersion = "0.8.12"
}

val coverageMinimums = mapOf(
    "kotlin-smtp-core" to "0.08".toBigDecimal(),
    "kotlin-smtp-spring-boot-starter" to "0.05".toBigDecimal(),
    "kotlin-smtp-relay-spring-boot-starter" to "0.05".toBigDecimal(),
)

apiValidation {
    // At this stage, only fix the core/relay "API boundary", excluding Spring wiring/impl modules.
    ignoredProjects.addAll(
        listOf(
            // root project is not a library module but for test harness/docs, so excluded from API validation.
            "kotlin-smtp",
            "kotlin-smtp-example-app",
            "kotlin-smtp-benchmarks",
            "kotlin-smtp-spring-boot-starter",
            "kotlin-smtp-relay-spring-boot-starter",
            "kotlin-smtp-relay-jakarta-mail",
        )
    )
}

subprojects {
    apply(plugin = "jacoco")

    tasks.withType<Test>().configureEach {
        finalizedBy(tasks.matching { it.name == "jacocoTestReport" })
    }

    tasks.matching { it.name == "jacocoTestReport" }.configureEach {
        dependsOn(tasks.named("test"))
    }

    tasks.matching { it.name == "jacocoTestCoverageVerification" }.configureEach {
        dependsOn(tasks.named("test"))
    }

    tasks.withType<org.gradle.testing.jacoco.tasks.JacocoReport>().configureEach {
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }

    tasks.withType<org.gradle.testing.jacoco.tasks.JacocoCoverageVerification>().configureEach {
        val minimum = coverageMinimums[project.name] ?: return@configureEach
        violationRules {
            rule {
                element = "BUNDLE"
                limit {
                    counter = "LINE"
                    value = "COVEREDRATIO"
                    this.minimum = minimum
                }
            }
        }
    }

    if (name in publishableModules) {
        apply(plugin = "com.vanniktech.maven.publish")

        // Maven Central Portal configuration
        extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            // Automatic publishing to Central Portal (automatic publish after deployment)
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

// In Gradle 8.x, when running apiDump + apiCheck together in the same build,
// task output dependency (implicit dependency) validation may fail.
// To ensure safety when developers manually run `apiDump` followed by `apiCheck`,
// we only guarantee execution order within the same project.
allprojects {
    tasks.matching { it.name == "apiCheck" }.configureEach {
        mustRunAfter("apiDump")
    }
}

// New Central Portal authentication settings
// GitHub Secrets required:
// - MAVEN_CENTRAL_USERNAME: central.sonatype.com User Token Username
// - MAVEN_CENTRAL_PASSWORD: central.sonatype.com User Token Password
// - SIGNING_KEY: GPG private key
// - SIGNING_PASSWORD: GPG passphrase
