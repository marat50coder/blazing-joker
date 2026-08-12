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
) {
    val hasDestination: Boolean
        get() = approved && !url.isNullOrEmpty()

    companion object {
        fun rejected(note: String): ChartAnswer =
            ChartAnswer(approved = false, url = null, expiresAt = null, note = note)

        fun fromJson(json: JSONObject): ChartAnswer {
            val ok = json.optBoolean("ok", false)
            val url = json.optString("url", "").ifEmpty { null }
            val expires: Long? = when (val raw = json.opt("expires")) {
                is Number -> raw.toLong()
                is String -> raw.toLongOrNull()
                else -> null
            }
            val note = json.optString("message", "").ifEmpty { null }
            return ChartAnswer(approved = ok, url = url, expiresAt = expires, note = note)
        }
    }
}
