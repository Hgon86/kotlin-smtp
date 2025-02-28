package com.crinity.kotlinsmtp

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class KotlinSmtpApplication

fun main(args: Array<String>): Unit = runBlocking {
    runApplication<KotlinSmtpApplication>(*args)
    awaitCancellation()
}