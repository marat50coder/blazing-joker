package com.blazingjoker.blazingjokergame

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * Lightweight sound-effect player backed by SoundPool. Loads short mp3 clips from assets/sfx.
 */
object Sfx {
    private var pool: SoundPool? = null
    private val ids = HashMap<String, Int>()
    private var loaded = false
    var enabled = true

    val CLICK = "click.mp3"
    val SPAWN = "spawn.mp3"
    val SHOOT = "shoot.mp3"
    val COIN = "coin.mp3"
    val WIN = "win.mp3"
    val LOSE = "lose.mp3"
    val BOSS = "boss.mp3"
    val BUILD = "build.mp3"
    val HIT = "hit.mp3"

    private val all = listOf(CLICK, SPAWN, SHOOT, COIN, WIN, LOSE, BOSS, BUILD, HIT)

    fun init(context: Context) {
        if (loaded) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val sp = SoundPool.Builder().setMaxStreams(8).setAudioAttributes(attrs).build()
        val am = context.applicationContext.assets
        for (name in all) {
            try {
                val afd = am.openFd("sfx/$name")
                ids[name] = sp.load(afd, 1)
                afd.close()
            } catch (_: Exception) {
                // Missing clip is non-fatal.
            }
        }
        pool = sp
        loaded = true
    }

    fun play(name: String, volume: Float = 1f) {
        if (!enabled) return
        val sp = pool ?: return
        val id = ids[name] ?: return
        sp.play(id, volume, volume, 1, 0, 1f)
    }
}
