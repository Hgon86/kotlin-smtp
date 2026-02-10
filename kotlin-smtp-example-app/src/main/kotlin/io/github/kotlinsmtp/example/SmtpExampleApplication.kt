package io.github.kotlinsmtp.example

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * kotlin-smtp starter를 소비하는 최소 예제 애플리케이션입니다.
 */
@SpringBootApplication
class SmtpExampleApplication

fun main(args: Array<String>) {
    runApplication<SmtpExampleApplication>(*args)
}
