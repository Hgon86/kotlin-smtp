package com.crinity.kotlinsmtp.utils

object Values {
    val whitespaceRegex = Regex("\\s+")
    const val MAX_MESSAGE_SIZE = 52_428_800 // 50MB in bytes
}
