package io.github.kotlinsmtp

import java.io.BufferedReader
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Shared SMTP test utilities for integration-level socket tests.
 *
 * Centralises protocol helpers that are otherwise duplicated across test classes.
 */

/** Reads and discards a multi-line EHLO (250-/250) response. */
fun BufferedReader.skipEhloResponse() {
    var line = readLine()
    while (line != null && (line.startsWith("250-") || line.startsWith("250 "))) {
        if (line.startsWith("250 ")) break
        line = readLine()
    }
}

/** Reads all lines of a multi-line EHLO (250-/250) response and returns them. */
fun BufferedReader.readEhloLines(): List<String> {
    val lines = mutableListOf<String>()
    var line = readLine()
    while (line != null && (line.startsWith("250-") || line.startsWith("250 "))) {
        lines.add(line)
        if (line.startsWith("250 ")) break
        line = readLine()
    }
    return lines
}

/** Wraps a plain socket in a trust-all TLS socket and performs the handshake. */
fun Socket.wrapToTls(): SSLSocket {
    val trustAll: TrustManager = object : X509TrustManager {
        override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
    }
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(null, arrayOf(trustAll), SecureRandom())

    val tls = ctx.socketFactory.createSocket(this, "localhost", port, true) as SSLSocket
    tls.useClientMode = true
    tls.startHandshake()
    // Brief pause to let server-side TLS state settle before I/O.
    Thread.sleep(50)
    return tls
}

/** Encodes AUTH PLAIN credentials as a single-line command. */
fun buildAuthPlainLine(username: String, password: String): String {
    val raw = "\u0000${username}\u0000${password}".toByteArray(Charsets.UTF_8)
    return "AUTH PLAIN ${Base64.getEncoder().encodeToString(raw)}\r\n"
}
