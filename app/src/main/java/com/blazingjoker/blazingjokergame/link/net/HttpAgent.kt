package com.blazingjoker.blazingjokergame.link.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tiny HTTP wrapper around HttpURLConnection. We deliberately avoid OkHttp
 * — bringing that transitive graph adds ~200 KB and, more importantly,
 * shows up as a common cluster marker in DEX metadata across gray-flow
 * sibling apps. HttpURLConnection is native to the platform, ships in
 * every apk regardless, and adds zero fingerprint.
 *
 * Every outbound request carries the forged UA from [AgentForge]; both
 * the HTTP client and the WebView share that single source of truth.
 */
internal object HttpAgent {

    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 20_000

    data class Reply(val status: Int, val body: String)

    suspend fun getText(
        endpoint: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 15_000L,
    ): Reply? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(timeoutMs) { doGet(endpoint, headers) }
    }

    suspend fun postJson(
        endpoint: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 18_000L,
    ): Reply? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(timeoutMs) { doPost(endpoint, body, headers) }
    }

    private fun doGet(endpoint: String, headers: Map<String, String>): Reply? = runCatching {
        val cx = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            useCaches = false
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", AgentForge.line)
            setRequestProperty("Accept", "application/json, */*;q=0.5")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val code = cx.responseCode
            val payload = readAllSafely(cx)
            Reply(code, payload)
        } finally {
            cx.disconnect()
        }
    }.getOrNull()

    private fun doPost(endpoint: String, body: String, headers: Map<String, String>): Reply? =
        runCatching {
            val cx = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "POST"
                doOutput = true
                useCaches = false
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", AgentForge.line)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            try {
                val bytes = body.toByteArray(Charsets.UTF_8)
                cx.setFixedLengthStreamingMode(bytes.size)
                cx.outputStream.use { os: OutputStream -> os.write(bytes) }
                val code = cx.responseCode
                val payload = readAllSafely(cx)
                Reply(code, payload)
            } finally {
                cx.disconnect()
            }
        }.getOrNull()

    private fun readAllSafely(cx: HttpURLConnection): String {
        val stream = try {
            if (cx.responseCode in 200..399) cx.inputStream else cx.errorStream
        } catch (_: Throwable) {
            cx.errorStream
        } ?: return ""
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
    }
}
