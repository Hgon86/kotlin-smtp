package io.github.kotlinsmtp.protocol.handler

import io.github.kotlinsmtp.model.SmtpUser


/**
 * 사용자 조회/검증을 위한 확장 포인트입니다.
 *
 * - VRFY 등에서 사용자 목록을 반환할 때 사용됩니다.
 */
public abstract class SmtpUserHandler {
    /**
     * @param searchTerm 검색어(이메일/아이디 등, 구현체 정책에 따름)
     * @return 검색된 사용자 목록(없으면 빈 컬렉션)
     */
    public abstract fun verify(searchTerm: String): Collection<SmtpUser>
}
