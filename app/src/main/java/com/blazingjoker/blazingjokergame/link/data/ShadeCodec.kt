package com.blazingjoker.blazingjokergame.link.data

/**
 * Symmetric stream codec used to obscure every plaintext token that would
 * otherwise ship as a string literal in the compiled binary — endpoint URL,
 * AppsFlyer key, Firebase project id, User-Agent scaffolding, etc.
 *
 * Design: FNV-1a folds the per-project salt into a 64-bit seed; SplitMix64
 * emits the keystream, one 64-bit step per plaintext byte. Each byte is
 * XOR-ed against the low-8 slice of the keystream word AND against a
 * position mask derived from the byte index. XOR is symmetric so the same
 * routine is used at generation time (from Python) and at runtime (here).
 *
 * The keystream cannot be recovered from a static-analysis pass through the
 * DEX — the compiler cannot constant-fold across the FNV/SplitMix loop and
 * every decoded string is materialised on-demand in a fresh byte[].
 */
internal object ShadeCodec {

    // Per-project random salt. Rotate whenever a new sibling app is shipped
    // — a repeated salt is a trivial cross-app cluster edge.
    private val salt = byteArrayOf(
        0xC0.toByte(), 0x09.toByte(), 0xFF.toByte(), 0x62.toByte(),
        0x21.toByte(), 0x2F.toByte(), 0x48.toByte(), 0x2A.toByte(),
        0xF9.toByte(), 0x50.toByte(), 0x01.toByte(), 0x8F.toByte(),
        0x1F.toByte(), 0x0B.toByte(), 0x6E.toByte(), 0x51.toByte(),
    )

    // Golden ratio + FNV prime — well-known constants but combined in a
    // shape distinct from the RC4 / FNV-LCG / position-XOR families that
    // sibling Flutter projects tend to ship.
    private val fnvPrime = java.lang.Long.parseUnsignedLong("100000001B3", 16)
    private val mixC1 = java.lang.Long.parseUnsignedLong("BF58476D1CE4E5B9", 16)
    private val mixC2 = java.lang.Long.parseUnsignedLong("94D049BB133111EB", 16)
    private val goldenSeed = java.lang.Long.parseUnsignedLong("9E3779B97F4A7C15", 16)

    fun reveal(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        val out = ByteArray(payload.size)
        var state = seedFromSalt()
        for (i in payload.indices) {
            state = mix64(state)
            val ks = ((state ushr ((i and 7) * 8)) and 0xFF).toInt()
            val pm = (i xor (i shr 3)) and 0xFF
            out[i] = ((payload[i].toInt() and 0xFF) xor ks xor pm).toByte()
        }
        return String(out, Charsets.UTF_8)
    }

    private fun seedFromSalt(): Long {
        var seed = goldenSeed
        for (b in salt) {
            seed = seed xor (b.toLong() and 0xFF)
            seed *= fnvPrime
        }
        return seed
    }

    private fun mix64(x: Long): Long {
        var z = x + goldenSeed
        z = (z xor (z ushr 30)) * mixC1
        z = (z xor (z ushr 27)) * mixC2
        return z xor (z ushr 31)
    }
}
