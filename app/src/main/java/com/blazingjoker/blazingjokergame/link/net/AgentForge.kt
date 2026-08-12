package com.blazingjoker.blazingjokergame.link.net

import android.os.Build
import com.blazingjoker.blazingjokergame.link.config.LinkConfig
import com.blazingjoker.blazingjokergame.link.data.ShadedTokens

/**
 * Assembles a real-device User-Agent used by BOTH the HTTP client hitting
 * the chart endpoint AND the WebView. Both consumers MUST take the value
 * from `AgentForge.line` so the two channels are byte-identical.
 *
 * Every UA scaffolding fragment (`Mozilla/5.0`, `(Linux; Android`,
 * ` Build/`, ` AppleWebKit/`, `(KHTML, like Gecko)`, ` Chrome/`,
 * ` Mobile Safari/`) resolves through `ShadedTokens` — the corresponding
 * plaintext substrings are absent from the DEX.
 *
 * The `appid/<bundle> appname/<name>` suffix is REQUIRED by the current
 * partner contract (see brief). Both suffix tokens are also encoded. If a
 * future partner accepts moving identity onto an HTTP header, drop the
 * suffix from `assemble()` and record the choice in a code comment above
 * that method (audit trail across sibling apps).
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

        val core = buildString {
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

        // GAME THEME CATEGORY: slot (partner-required identity suffix
        // present; all suffix tokens encoded in ShadedTokens).
        val idToken = ShadedTokens.uaAppIdToken()
        val nameToken = ShadedTokens.uaAppNameToken()
        val nameValue = ShadedTokens.uaAppNameValue()
        if (idToken.isEmpty()) return core
        return buildString {
            append(core)
            append(' ')
            append(idToken)
            append(LinkConfig.APPLICATION_ID)
            append(' ')
            append(nameToken)
            append(nameValue)
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
