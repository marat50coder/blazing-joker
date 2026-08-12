package com.blazingjoker.blazingjokergame.link.data

/**
 * Outcome of a single boot-pipeline run. The dispatcher (`LoadingActivity`)
 * `when`-branches on this sealed hierarchy and only there decides which
 * Activity to launch. Ad-hoc `startActivity(...)` calls from inside the
 * pipeline are forbidden — they scatter routing logic and defeat the
 * "single decision" property that makes the flow debuggable.
 */
internal sealed class Berth {
    /** Show the native tower-defence game. */
    data object Native : Berth()

    /** Show the WebView shell pointed at [url]. */
    data class Web(val url: String, val fromColdPush: Boolean = false) : Berth()

    /** No usable connection — user sees the retry screen. */
    data class LostSignal(val previouslyOnNative: Boolean) : Berth()
}

/**
 * Persisted routing memory across launches. Wire strings are load-bearing:
 * a running install with `web` in its prefs must keep loading the WebView
 * even after the app upgrade; do not rename the wire values.
 */
internal enum class LastCourse(val wire: String) {
    Unset("unset"),
    Web("web"),
    Native("native");

    companion object {
        fun parse(v: String?): LastCourse = when (v) {
            "web", "portal" -> Web
            "native", "game" -> Native
            else -> Unset
        }
    }
}
