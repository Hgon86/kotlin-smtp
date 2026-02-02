package com.crinity.kotlinsmtp.protocol.handler

import java.nio.file.Files
import java.nio.file.Path

/**
 * 로컬 파일 기반 EXPN 구현
 *
 * 디렉터리 구조:
 * - listsDir/<list>.txt
 *
 * 파일 포맷:
 * - 빈 줄/공백 줄 무시
 * - '#' 으로 시작하는 줄은 주석으로 무시
 * - 각 줄은 멤버(이메일 주소/설명 문자열 등)를 그대로 반환
 *
 * TODO(DB/S3/MSA):
 * - 메일링 리스트/멤버십 저장소를 DB 또는 Directory 서비스로 이관
 * - ACL(누가 어떤 리스트를 EXPN할 수 있는지) 정책 엔진화
 */
class LocalFileMailingListHandler(
    private val listsDir: Path,
    private val maxMembers: Int = 200,
) : SmtpMailingListHandler {

    override fun expand(listName: String): List<String> {
        val key = normalizeListKey(listName)
        if (key.isEmpty()) return emptyList()

        val file = listsDir.resolve("$key.txt")
        if (!Files.exists(file)) return emptyList()

        return runCatching {
            Files.newBufferedReader(file).useLines { lines ->
                lines
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .take(maxMembers)
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    private fun normalizeListKey(input: String): String {
        // "list@domain" 형태면 local-part만 사용(기능 우선, 도메인별 분리는 TODO)
        val raw = input.trim().substringBeforeLast('@')
        // 파일명 안전화
        return raw.replace(Regex("[\\\\/:*?\"<>|\\s]"), "_").take(128)
    }
}

