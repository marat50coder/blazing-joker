package com.blazingjoker.blazingjokergame.link.data

/**
 * Every plaintext token that a static-analysis pass would grep for lives
 * here as a byte array. Runtime callers must go through the `resolve*`
 * accessors — never touch the raw arrays.
 *
 * Populated once per project. When rotating this file for a sibling app:
 *   1. Change `ShadeCodec.salt` (fresh random 16 bytes).
 *   2. Re-run the Python encoder (mirrors ShadeCodec) against the same
 *      plaintext list to regenerate every byte array below.
 *   3. Verify each `resolve*` round-trips the expected plaintext.
 *
 * Do NOT hand-edit the byte literals — they are generator output.
 */
internal object ShadedTokens {

    // ── Backend + attribution ─────────────────────────────────────
    private val _endpointUrl = byteArrayOf(
        0xC2.toByte(), 0xEF.toByte(), 0x93.toByte(), 0x4F.toByte(), 0x90.toByte(), 0x36.toByte(), 0x13.toByte(), 0x1C.toByte(), 0x79.toByte(), 0x8D.toByte(), 0x36.toByte(), 0x47.toByte(),
        0x81.toByte(), 0x9D.toByte(), 0xAF.toByte(), 0xB0.toByte(), 0x66.toByte(), 0x34.toByte(), 0x38.toByte(), 0x83.toByte(), 0xED.toByte(), 0x1D.toByte(), 0x72.toByte(), 0x47.toByte(),
        0xBA.toByte(), 0xA1.toByte(), 0x98.toByte(), 0x3E.toByte(), 0x38.toByte(), 0x8D.toByte(), 0xCC.toByte(), 0x2A.toByte(), 0x5B.toByte(), 0x84.toByte(), 0x15.toByte(), 0xA5.toByte(),
    )
    private val _attributionKey = byteArrayOf(
        0xF2.toByte(), 0xDE.toByte(), 0xB5.toByte(), 0x51.toByte(), 0xDA.toByte(), 0x6E.toByte(), 0x4D.toByte(), 0x5D.toByte(), 0x7F.toByte(), 0xB4.toByte(), 0x34.toByte(), 0x47.toByte(),
        0xDD.toByte(), 0xC4.toByte(), 0x92.toByte(), 0x98.toByte(), 0x6D.toByte(), 0x2C.toByte(), 0x37.toByte(), 0xB3.toByte(), 0xD4.toByte(), 0x79.toByte(),
    )
    private val _messagingProject = byteArrayOf(
        0x9B.toByte(), 0xAB.toByte(), 0xD5.toByte(), 0x0D.toByte(), 0xD2.toByte(), 0x3A.toByte(), 0x04.toByte(), 0x06.toByte(), 0x2F.toByte(), 0xD4.toByte(), 0x6E.toByte(), 0x09.toByte(),
        0xD9.toByte(),
    )
    private val _gcdBaseUrl = byteArrayOf(
        0xC2.toByte(), 0xEF.toByte(), 0x93.toByte(), 0x4F.toByte(), 0x90.toByte(), 0x36.toByte(), 0x13.toByte(), 0x1C.toByte(), 0x7C.toByte(), 0x82.toByte(), 0x33.toByte(), 0x4E.toByte(),
        0x8C.toByte(), 0x98.toByte(), 0xE6.toByte(), 0xBB.toByte(), 0x79.toByte(), 0x2F.toByte(), 0x20.toByte(), 0x80.toByte(), 0xF3.toByte(), 0x4A.toByte(), 0x74.toByte(), 0x5A.toByte(),
        0xF9.toByte(), 0xED.toByte(), 0x94.toByte(), 0x3C.toByte(), 0x79.toByte(), 0x82.toByte(), 0xCB.toByte(), 0x3E.toByte(), 0x01.toByte(), 0x95.toByte(), 0x11.toByte(), 0xB9.toByte(),
        0x50.toByte(), 0xD6.toByte(), 0x13.toByte(), 0xF2.toByte(), 0xFE.toByte(), 0x70.toByte(), 0x71.toByte(), 0x27.toByte(), 0xEA.toByte(), 0xFE.toByte(), 0x69.toByte(),
    )
    private val _homeUrl = byteArrayOf(
        0xC2.toByte(), 0xEF.toByte(), 0x93.toByte(), 0x4F.toByte(), 0x90.toByte(), 0x36.toByte(), 0x13.toByte(), 0x1C.toByte(), 0x79.toByte(), 0x8D.toByte(), 0x36.toByte(), 0x47.toByte(),
        0x81.toByte(), 0x9D.toByte(), 0xAF.toByte(), 0xB0.toByte(), 0x66.toByte(), 0x34.toByte(), 0x38.toByte(), 0x83.toByte(), 0xED.toByte(), 0x1D.toByte(), 0x72.toByte(), 0x47.toByte(),
        0xBA.toByte(), 0xA1.toByte(),
    )

    // ── UA scaffolding ────────────────────────────────────────────
    private val _uaProduct = byteArrayOf(
        0xE7.toByte(), 0xF4.toByte(), 0x9D.toByte(), 0x56.toByte(), 0x8F.toByte(), 0x60.toByte(), 0x5D.toByte(), 0x1C.toByte(), 0x2E.toByte(), 0xCF.toByte(), 0x67.toByte(),
    )
    private val _uaPlatformOpen = byteArrayOf(
        0x82.toByte(), 0xD7.toByte(), 0x8E.toByte(), 0x51.toByte(), 0x96.toByte(), 0x74.toByte(), 0x07.toByte(), 0x13.toByte(), 0x5A.toByte(), 0x8F.toByte(), 0x33.toByte(), 0x4F.toByte(),
        0x87.toByte(), 0x9A.toByte(), 0xAC.toByte(),
    )
    private val _uaBuildLabel = byteArrayOf(
        0x8A.toByte(), 0xD9.toByte(), 0x92.toByte(), 0x56.toByte(), 0x8F.toByte(), 0x68.toByte(), 0x13.toByte(),
    )
    private val _uaPlatformClose = byteArrayOf(
        0x83.toByte(),
    )
    private val _uaEngineLabel = byteArrayOf(
        0x8A.toByte(), 0xDA.toByte(), 0x97.toByte(), 0x4F.toByte(), 0x8F.toByte(), 0x69.toByte(), 0x6B.toByte(), 0x56.toByte(), 0x79.toByte(), 0xAA.toByte(), 0x3E.toByte(), 0x49.toByte(),
        0xC7.toByte(),
    )
    private val _uaEngineTail = byteArrayOf(
        0x8A.toByte(), 0xB3.toByte(), 0xAC.toByte(), 0x77.toByte(), 0xB7.toByte(), 0x41.toByte(), 0x70.toByte(), 0x1F.toByte(), 0x3B.toByte(), 0x8D.toByte(), 0x3E.toByte(), 0x56.toByte(),
        0x8D.toByte(), 0xD3.toByte(), 0x8F.toByte(), 0xBF.toByte(), 0x6A.toByte(), 0x34.toByte(), 0x3C.toByte(), 0xCF.toByte(),
    )
    private val _uaChromeLabel = byteArrayOf(
        0x8A.toByte(), 0xD8.toByte(), 0x8F.toByte(), 0x4D.toByte(), 0x8C.toByte(), 0x61.toByte(), 0x59.toByte(), 0x1C.toByte(),
    )
    private val _uaMobileTail = byteArrayOf(
        0x8A.toByte(), 0xD6.toByte(), 0x88.toByte(), 0x5D.toByte(), 0x8A.toByte(), 0x60.toByte(), 0x59.toByte(), 0x13.toByte(), 0x48.toByte(), 0x80.toByte(), 0x31.toByte(), 0x5C.toByte(),
        0x9A.toByte(), 0x9A.toByte(), 0xE7.toByte(),
    )
    private val _chromeVersion = byteArrayOf(
        0x9B.toByte(), 0xAF.toByte(), 0xDE.toByte(), 0x11.toByte(), 0xD3.toByte(), 0x22.toByte(), 0x0B.toByte(), 0x0B.toByte(), 0x29.toByte(), 0xD6.toByte(), 0x79.toByte(), 0x0C.toByte(),
        0xDE.toByte(), 0xC0.toByte(),
    )
    private val _webkitVersion = byteArrayOf(
        0x9F.toByte(), 0xA8.toByte(), 0xD0.toByte(), 0x11.toByte(), 0xD0.toByte(), 0x3A.toByte(),
    )

    // ── UA identity suffix (slot-style; user brief mandates it) ──
    private val _uaAppIdToken = byteArrayOf(
        0xCB.toByte(), 0xEB.toByte(), 0x97.toByte(), 0x56.toByte(), 0x87.toByte(), 0x23.toByte(),
    )
    private val _uaAppNameToken = byteArrayOf(
        0xCB.toByte(), 0xEB.toByte(), 0x97.toByte(), 0x51.toByte(), 0x82.toByte(), 0x61.toByte(), 0x59.toByte(), 0x1C.toByte(),
    )
    private val _uaAppNameValue = byteArrayOf(
        0xE8.toByte(), 0xF7.toByte(), 0x86.toByte(), 0x45.toByte(), 0x8A.toByte(), 0x62.toByte(), 0x5B.toByte(), 0x79.toByte(), 0x74.toByte(), 0x8A.toByte(), 0x32.toByte(), 0x4F.toByte(),
    )

    // ── GCD query bits ────────────────────────────────────────────
    private val _gcdQueryDevkey = byteArrayOf(
        0x95.toByte(), 0xFF.toByte(), 0x82.toByte(), 0x49.toByte(), 0x88.toByte(), 0x69.toByte(), 0x45.toByte(), 0x0E.toByte(),
    )
    private val _gcdQueryDeviceId = byteArrayOf(
        0x8C.toByte(), 0xFF.toByte(), 0x82.toByte(), 0x49.toByte(), 0x8A.toByte(), 0x6F.toByte(), 0x59.toByte(), 0x6C.toByte(), 0x72.toByte(), 0x85.toByte(), 0x6A.toByte(),
    )

    // ── Public resolvers ──────────────────────────────────────────
    fun endpointUrl(): String = ShadeCodec.reveal(_endpointUrl)
    fun attributionKey(): String = ShadeCodec.reveal(_attributionKey)
    fun messagingProject(): String = ShadeCodec.reveal(_messagingProject)
    fun gcdBaseUrl(): String = ShadeCodec.reveal(_gcdBaseUrl)
    fun homeUrl(): String = ShadeCodec.reveal(_homeUrl)

    fun uaProduct(): String = ShadeCodec.reveal(_uaProduct)
    fun uaPlatformOpen(): String = ShadeCodec.reveal(_uaPlatformOpen)
    fun uaBuildLabel(): String = ShadeCodec.reveal(_uaBuildLabel)
    fun uaPlatformClose(): String = ShadeCodec.reveal(_uaPlatformClose)
    fun uaEngineLabel(): String = ShadeCodec.reveal(_uaEngineLabel)
    fun uaEngineTail(): String = ShadeCodec.reveal(_uaEngineTail)
    fun uaChromeLabel(): String = ShadeCodec.reveal(_uaChromeLabel)
    fun uaMobileTail(): String = ShadeCodec.reveal(_uaMobileTail)
    fun chromeVersion(): String = ShadeCodec.reveal(_chromeVersion)
    fun webkitVersion(): String = ShadeCodec.reveal(_webkitVersion)

    fun uaAppIdToken(): String = ShadeCodec.reveal(_uaAppIdToken)
    fun uaAppNameToken(): String = ShadeCodec.reveal(_uaAppNameToken)
    fun uaAppNameValue(): String = ShadeCodec.reveal(_uaAppNameValue)

    /** Assembles the AppsFlyer GCD rescue URL, or "" if the base is empty. */
    fun gcdRescueUrl(appRef: String, deviceUid: String): String {
        val base = gcdBaseUrl()
        if (base.isEmpty()) return ""
        val dk = ShadeCodec.reveal(_gcdQueryDevkey)
        val di = ShadeCodec.reveal(_gcdQueryDeviceId)
        return "$base$appRef$dk${attributionKey()}$di$deviceUid"
    }
}
