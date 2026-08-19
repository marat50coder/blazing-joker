package com.blazingjoker.blazingjokergame.link.data

import org.json.JSONObject

/**
 * Parsed body of a chart-endpoint response. The wire schema is
 * `{ ok: bool, url: string?, expires: int?, message: string? }` — those
 * exact keys are load-bearing (backend contract). Do not rename them.
 */
internal data class ChartAnswer(
    val approved: Boolean,
    val url: String?,
    val expiresAt: Long?,
    val note: String?,
    /**
     * True iff the endpoint actually replied with a parseable JSON body
     * (HTTP 200 and JSON that survived [fromJson]). Network timeouts,
     * non-200 responses and malformed bodies all set this to false — the
     * client cannot commit to a "not for you" decision on those, since
     * that means "we asked and the backend said no", not "we could not
     * ask". Pilot uses this to decide whether the fresh-launch verdict
     * should stick or the next launch should get another swing at
     * attribution (pitfalls: OneLink → offline install → online retry
     * chose Native on empty attribution and stuck).
     */
    val serverResponded: Boolean,
) {
    val hasDestination: Boolean
        get() = approved && !url.isNullOrEmpty()

    companion object {
        fun rejected(note: String): ChartAnswer = ChartAnswer(
            approved = false,
            url = null,
            expiresAt = null,
            note = note,
            serverResponded = false,
        )

        fun fromJson(json: JSONObject): ChartAnswer {
            val ok = json.optBoolean("ok", false)
            val url = json.optString("url", "").ifEmpty { null }
            val expires: Long? = when (val raw = json.opt("expires")) {
                is Number -> raw.toLong()
                is String -> raw.toLongOrNull()
                else -> null
            }
            val note = json.optString("message", "").ifEmpty { null }
            return ChartAnswer(
                approved = ok,
                url = url,
                expiresAt = expires,
                note = note,
                serverResponded = true,
            )
        }
    }
}
