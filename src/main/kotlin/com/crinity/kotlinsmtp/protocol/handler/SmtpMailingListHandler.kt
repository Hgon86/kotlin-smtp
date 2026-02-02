package com.crinity.kotlinsmtp.protocol.handler

/**
 * EXPN(메일링 리스트 확장) 처리용 핸들러
 *
 * - 기능 구현 우선: 로컬 파일/디렉터리 기반 구현체를 제공하고
 * - TODO(DB/MSA): 운영에서는 DB/Directory/Policy 서비스로 이관
 */
interface SmtpMailingListHandler {
    /**
     * @param listName EXPN 인자(예: "dev-team" 또는 "dev-team@example.com")
     * @return 확장된 멤버 표현 문자열 목록(각 라인은 SMTP 멀티라인 응답에 그대로 사용)
     */
    fun expand(listName: String): List<String>
}

