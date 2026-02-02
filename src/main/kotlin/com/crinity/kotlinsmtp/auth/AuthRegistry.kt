package com.crinity.kotlinsmtp.auth

/**
 * 간단한 전역 인증 서비스 레지스트리
 * DI가 어려운 경로에서 AUTH 명령이 접근할 수 있도록 제공
 */
object AuthRegistry {
    @Volatile
    var service: AuthService? = null
}
