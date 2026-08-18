package com.blazingjoker.blazingjokergame.link.net

import android.util.Log
import com.blazingjoker.blazingjokergame.link.config.LinkConfig
import com.blazingjoker.blazingjokergame.link.data.ChartAnswer
import com.blazingjoker.blazingjokergame.link.store.StowageBox
import org.json.JSONObject

/**
 * POSTs the assembled attribution + device body to the chart endpoint and
 * parses the verdict. The backend is the sole source of truth for the
 * routing decision — the client MUST NOT classify partner-side content
 * (no `deposit / login / cashier / register` regex, no funnel emission).
 * If any of those show up, the review is over.
 */
internal class ChartFetcher(private val stowage: StowageBox) {

    suspend fun ask(bodyJson: JSONObject): ChartAnswer {
        val endpoint = LinkConfig.endpointUrl()
        if (endpoint.isEmpty()) {
            Log.w(TAG, "endpoint empty — skipping POST")
            return ChartAnswer.rejected("endpoint_missing")
        }

        Log.d(TAG, "POST → $endpoint")
        Log.d(TAG, "body → $bodyJson")

        val reply = HttpAgent.postJson(
            endpoint = endpoint,
            body = bodyJson.toString(),
            timeoutMs = LinkConfig.CHART_TIMEOUT_MS,
        ) ?: run {
            Log.w(TAG, "POST returned null (timeout / io error)")
            return ChartAnswer.rejected("network_timeout")
        }

        Log.d(TAG, "← HTTP ${reply.status}; body: ${reply.body.take(500)}")

        if (reply.status != 200) {
            return ChartAnswer.rejected("http_${reply.status}")
        }

        val answer = runCatching {
            ChartAnswer.fromJson(JSONObject(reply.body))
        }.getOrElse { return ChartAnswer.rejected("malformed") }

        if (answer.hasDestination) {
            stowage.cacheDestination(answer.url!!, answer.expiresAt)
        }
        return answer
    }

    companion object {
        private const val TAG = "ChartFetcher"
    }
}
