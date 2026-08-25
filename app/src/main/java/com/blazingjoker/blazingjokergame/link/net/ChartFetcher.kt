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

        // Try to parse the body regardless of HTTP status. Backends
        // sometimes serve a well-formed verdict envelope on 404/403
        // ("No data for this device"), and treating that as "we
        // couldn't reach the server" would keep re-asking forever
        // when the answer is actually "the backend has nothing for
        // you". Only 5xx and timeouts stay `rejected` — those are
        // "we could not ask", not "we asked and the answer was no".
        if (reply.status in 500..599) {
            return ChartAnswer.rejected("http_${reply.status}")
        }
        val parsed = runCatching {
            ChartAnswer.fromJson(JSONObject(reply.body))
        }.getOrNull()
        if (parsed == null) {
            Log.w(TAG, "malformed body on HTTP ${reply.status}")
            return ChartAnswer.rejected("malformed_${reply.status}")
        }

        if (parsed.hasDestination) {
            stowage.cacheDestination(parsed.url!!, parsed.expiresAt)
        }
        return parsed
    }

    companion object {
        private const val TAG = "ChartFetcher"
    }
}
