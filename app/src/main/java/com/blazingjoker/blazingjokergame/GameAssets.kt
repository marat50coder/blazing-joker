package com.blazingjoker.blazingjokergame

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Loads and caches game bitmaps from the app's assets folder. */
class GameAssets(context: Context) {

    private val am = context.applicationContext.assets
    private val cache = HashMap<String, Bitmap>()

    fun load(path: String): Bitmap? {
        cache[path]?.let { return it }
        return try {
            am.open(path).use { input ->
                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                val bmp = BitmapFactory.decodeStream(input, null, opts)
                if (bmp != null) cache[path] = bmp
                bmp
            }
        } catch (_: Exception) {
            null
        }
    }

    // Sprites
    val enemy1 by lazy { load("sprite/enemy1.png") }
    val enemy2 by lazy { load("sprite/enemy2.png") }
    val enemy3 by lazy { load("sprite/enemy3.png") }
    val enemy4 by lazy { load("sprite/enemy4.png") }
    val towerFire by lazy { load("sprite/tower_fire.png") }
    val towerIce by lazy { load("sprite/tower_ice.png") }
    val towerShadow by lazy { load("sprite/tower_shadow.png") }
    val towerLight by lazy { load("sprite/tower_light.png") }
    val core by lazy { load("sprite/core.png") }
    val crystal by lazy { load("sprite/crystal.png") }
    val base by lazy { load("sprite/base.png") }
    val chest by lazy { load("sprite/chest.png") }

    // Backgrounds
    val gameBg by lazy { load("bg/game_bg.jpg") }
    val victoryBg by lazy { load("bg/victory_bg.jpg") }
    val defeatBg by lazy { load("bg/defeat_bg.jpg") }

    fun enemyBitmap(type: Int): Bitmap? = when (type) {
        0 -> enemy1
        1 -> enemy2
        2 -> enemy3
        else -> enemy4
    }

    fun towerBitmap(type: Int): Bitmap? = when (type) {
        0 -> towerFire
        1 -> towerIce
        2 -> towerShadow
        else -> towerLight
    }
}
