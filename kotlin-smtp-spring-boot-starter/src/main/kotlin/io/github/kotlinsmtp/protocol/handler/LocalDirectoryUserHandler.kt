package io.github.kotlinsmtp.protocol.handler

import io.github.kotlinsmtp.model.SmtpUser
import java.nio.file.Files
import java.nio.file.Path

/**
 * 로컬 파일 시스템(mailboxDir) 기반의 간단한 사용자 검증 핸들러
 *
 * - 기능 구현 우선: VRFY를 제한된 환경에서 켜고 싶을 때 최소 구현으로 사용
 * - TODO(DB/MSA): 실제 프로덕션에서는 DB/Directory 서비스(또는 Policy 서비스)로 이관
 */
class LocalDirectoryUserHandler(
    private val mailboxDir: Path,
    private val localDomain: String,
) : SmtpUserHandler() {
    private val mailboxRoot: Path = mailboxDir.toAbsolutePath().normalize()

    override fun verify(searchTerm: String): Collection<SmtpUser> {
        val term = searchTerm.trim()
        if (term.isEmpty()) return emptyList()

        val (localPart, domain) = if (term.contains('@')) {
            val lp = term.substringBeforeLast('@')
            val d = term.substringAfterLast('@')
            lp to d
        } else {
            term to localDomain
        }

        // 외부 도메인은 로컬 사용자 저장소로 검증할 수 없습니다.
        if (!domain.equals(localDomain, ignoreCase = true)) return emptyList()

        val sanitized = sanitizeUsername(localPart)
        val userMailbox = mailboxRoot.resolve(sanitized).normalize()
        if (!userMailbox.startsWith(mailboxRoot)) return emptyList()
        if (!Files.exists(userMailbox)) return emptyList()

        return listOf(SmtpUser(localPart = localPart, domain = localDomain, username = localPart))
    }

    private fun sanitizeUsername(username: String): String =
        username.trim().map { ch ->
            when {
                ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-' || ch == '+' -> ch
                else -> '_'
            }
        }.joinToString("").take(128).let { sanitized ->
            if (sanitized.isBlank() || sanitized == "." || sanitized == "..") "_" else sanitized
        }
}
