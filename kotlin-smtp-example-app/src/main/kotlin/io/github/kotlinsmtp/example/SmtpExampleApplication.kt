package io.github.kotlinsmtp.example

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Minimal example application consuming kotlin-smtp starter.
 */
@SpringBootApplication
class SmtpExampleApplication

fun main(args: Array<String>) {
    runApplication<SmtpExampleApplication>(*args)
}
