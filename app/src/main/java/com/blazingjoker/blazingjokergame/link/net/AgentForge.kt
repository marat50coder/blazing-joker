package com.blazingjoker.blazingjokergame.link.net

import android.os.Build
import com.blazingjoker.blazingjokergame.link.data.ShadedTokens

/**
 * Assembles a real-device User-Agent used by BOTH the HTTP client hitting
 * the chart endpoint AND the WebView. Both consumers MUST take the value
 * from `AgentForge.line` so the two channels are byte-identical.
 *
 * Every UA scaffolding fragment resolves through `ShadedTokens` — the
 * corresponding plaintext substrings are absent from the compiled DEX.
 * The identifier suffix that some partner contracts append to the UA is
 * NOT emitted here; identity travels only through the POST body when
 * the chart is fetched.
 */
internal object AgentForge {

    // Lazily assembled once per process. The manifest-supplied device data
    // never changes at runtime and building this string a second time is
    // pure waste; guard behind a lock so a race between two boot paths
    // cannot double-materialise.
    @Volatile
    private var cached: String? = null
    private val lock = Any()

    /** Access from anywhere that needs the browser identity. */
    val line: String
        get() {
            cached?.let { return it }
            synchronized(lock) {
                cached?.let { return it }
                val built = compose()
                cached = built
                return built
            }
        }

    private fun compose(): String {
        val release = safeString(Build.VERSION.RELEASE, "14")
        val brand = titleCase(safeString(Build.BRAND, "Google"))
        val model = safeString(Build.MODEL, "Pixel 8")
        val buildTag = safeString(Build.DISPLAY, safeString(Build.ID, "UP1A.231005.007"))

        val product = ShadedTokens.uaProduct()
        val platOpen = ShadedTokens.uaPlatformOpen()
        val buildLabel = ShadedTokens.uaBuildLabel()
        val platClose = ShadedTokens.uaPlatformClose()
        val engineLabel = ShadedTokens.uaEngineLabel()
        val engineTail = ShadedTokens.uaEngineTail()
        val chromeLabel = ShadedTokens.uaChromeLabel()
        val mobileTail = ShadedTokens.uaMobileTail()

        val chrome = orElse(ShadedTokens.chromeVersion(), "149.0.7827.163")
        val webkit = orElse(ShadedTokens.webkitVersion(), "537.36")

        return buildString {
            append(product)
            append(' ')
            append(platOpen)
            append(' ')
            append(release)
            append("; ")
            append(brand)
            append(' ')
            append(model)
            append(buildLabel)
            append(buildTag)
            append(platClose)
            append(engineLabel)
            append(webkit)
            append(engineTail)
            append(chromeLabel)
            append(chrome)
            append(mobileTail)
            append(webkit)
        }
    }

    private fun safeString(value: String?, fallback: String): String =
        if (value.isNullOrEmpty()) fallback else value

    private fun titleCase(v: String): String {
        if (v.isEmpty()) return v
        return v[0].uppercaseChar() + v.substring(1)
    }

    private fun orElse(v: String, fb: String): String = if (v.isEmpty()) fb else v
}
