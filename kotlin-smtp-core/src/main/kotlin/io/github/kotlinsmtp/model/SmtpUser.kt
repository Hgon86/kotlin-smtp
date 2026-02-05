package io.github.kotlinsmtp.model

/**
 * SMTP 사용자 모델입니다.
 *
 * - [SmtpUserHandler] 구현체가 조회 결과로 반환하는 최소 단위입니다.
 * - 표시/로그/프로토콜 응답에서 재사용할 수 있도록 이메일 표현을 함께 제공합니다.
 *
 * @property localPart 이메일 로컬 파트(@ 앞)
 * @property domain 이메일 도메인(@ 뒤)
 * @property username 표시용 사용자명(선택)
 */
public class SmtpUser(
    public val localPart: String,
    public val domain: String,
    public val username: String? = null,
) {
    /** 완성된 이메일 주소(localPart@domain) */
    public val email: String
        get() = "$localPart@$domain"

    /** SMTP 응답/로그에서 사용할 문자열 표현 */
    public val stringRepresentation: String by lazy {
        val e = email
        if (username != null) "$username <$e>" else e
    }
}
