package io.github.kotlinsmtp.spool

import io.github.kotlinsmtp.model.RcptDsn
import org.json.JSONObject
import java.nio.file.Path
import java.time.Instant

/**
 * 스풀 메타데이터 JSON 직렬화/역직렬화를 담당합니다.
 */
internal object SpoolMetadataJsonCodec {
    /**
     * 메타데이터를 JSON 문자열로 직렬화합니다.
     *
     * @param meta 직렬화 대상 메타데이터
     * @return JSON 문자열
     */
    fun toJson(meta: SpoolMetadata): String {
        val json = JSONObject()
            .put("id", meta.id)
            .put("attempt", meta.attempt)
            .put("next", meta.nextAttemptAt.toEpochMilli())
            .put("sender", meta.sender ?: "")
            .put("recipients", meta.recipients)
            .put("messageId", meta.messageId)
            .put("authenticated", meta.authenticated)
            .put("peerAddress", meta.peerAddress ?: "")
            .put("dsnRet", meta.dsnRet ?: "")
            .put("dsnEnvid", meta.dsnEnvid ?: "")

        val rcptDsnJson = JSONObject()
        for ((rcpt, dsn) in meta.rcptDsn) {
            rcptDsnJson.put(
                rcpt,
                JSONObject()
                    .put("notify", dsn.notify ?: "")
                    .put("orcpt", dsn.orcpt ?: ""),
            )
        }
        json.put("rcptDsn", rcptDsnJson)
        return json.toString()
    }

    /**
     * JSON 문자열을 메타데이터로 역직렬화합니다.
     *
     * @param rawPath 스풀 메시지 식별 경로
     * @param rawJson JSON 문자열
     * @return 파싱된 메타데이터
     */
    fun fromJson(rawPath: Path, rawJson: String): SpoolMetadata {
        val json = JSONObject(rawJson)
        val id = json.getString("id")
        val attempt = json.optInt("attempt", 0)
        val next = json.optLong("next", Instant.now().toEpochMilli())
        val sender = json.optString("sender").ifBlank { null }
        val recipientsJson = json.optJSONArray("recipients")
        val recipients = buildList {
            if (recipientsJson != null) {
                for (i in 0 until recipientsJson.length()) add(recipientsJson.getString(i))
            }
        }.toMutableList()
        val messageId = json.optString("messageId", "?")
        val authenticated = json.optBoolean("authenticated", false)
        val peerAddress = json.optString("peerAddress").ifBlank { null }
        val dsnRet = json.optString("dsnRet").ifBlank { null }
        val dsnEnvid = json.optString("dsnEnvid").ifBlank { null }

        val rcptDsnObj = json.optJSONObject("rcptDsn")
        val rcptDsn = linkedMapOf<String, RcptDsn>()
        if (rcptDsnObj != null) {
            for (key in rcptDsnObj.keySet()) {
                val obj = rcptDsnObj.optJSONObject(key) ?: continue
                val notify = obj.optString("notify").ifBlank { null }
                val orcpt = obj.optString("orcpt").ifBlank { null }
                rcptDsn[key] = RcptDsn(notify = notify, orcpt = orcpt)
            }
        }

        return SpoolMetadata(
            id = id,
            rawPath = rawPath,
            sender = sender,
            recipients = recipients,
            messageId = messageId,
            authenticated = authenticated,
            peerAddress = peerAddress,
            dsnRet = dsnRet,
            dsnEnvid = dsnEnvid,
            rcptDsn = rcptDsn.toMutableMap(),
            attempt = attempt,
            nextAttemptAt = Instant.ofEpochMilli(next),
        )
    }
}
