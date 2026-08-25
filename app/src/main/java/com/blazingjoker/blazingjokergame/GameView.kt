package com.blazingjoker.blazingjokergame

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.hypot

/**
 * Self-contained vertical tower-defense for Blazing Joker.
 *
 * The player defends the circus stage (core) at the bottom of the arena. Enemies from the
 * illusion dimension march down a winding path from the top. The player spends magic crystals
 * to place and upgrade circus artists (elemental towers) on pads, which auto-attack enemies.
 */
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    // ---- Game constants -------------------------------------------------------------------
    private val TOTAL_WAVES = 8
    private val START_CRYSTALS = 190
    private val START_LIVES = 20

    private val towerNames = arrayOf("Fire Mage", "Frost Mime", "Shadow Acrobat", "Light Seer")
    private val towerRole = arrayOf("Area splash", "Slows foes", "Rapid fire", "Long range")
    private val towerCost = intArrayOf(60, 55, 70, 95)
    private val towerBaseDamage = floatArrayOf(24f, 9f, 15f, 60f)
    private val towerRangeFactor = floatArrayOf(0.30f, 0.29f, 0.26f, 0.44f) // * width
    private val towerFireRate = floatArrayOf(0.95f, 1.05f, 2.3f, 0.5f)       // shots per second
    private val towerColors = intArrayOf(
        Color.parseColor("#FF7A18"),
        Color.parseColor("#5AD1FF"),
        Color.parseColor("#B060FF"),
        Color.parseColor("#FFE08A")
    )

    private val enemyNames = arrayOf("Shadow Spectator", "Living Mask", "Cursed Marionette", "Circus Monster")
    private val enemyBaseHp = floatArrayOf(60f, 42f, 150f, 300f)
    private val enemySpeedFactor = floatArrayOf(1.0f, 1.65f, 0.72f, 0.9f)
    private val enemyReward = intArrayOf(9, 11, 18, 34)
    private val enemyDamage = intArrayOf(1, 1, 2, 3)
    private val enemySizeFactor = floatArrayOf(0.135f, 0.12f, 0.14f, 0.18f)

    // ---- State ----------------------------------------------------------------------------
    private enum class Phase { PREP, RUNNING, WON, LOST }

    private var phase = Phase.PREP
    private var paused = false

    private var crystals = START_CRYSTALS
    private var lives = START_LIVES
    private var wave = 0 // 0-based index of the wave to start next / currently running

    // Endless mode: after clearing the final scripted wave the player may
    // opt into procedurally generated waves that keep the arena open until
    // the core falls. Composition and enemy HP both keep scaling with the
    // wave index — the existing `enemyBaseHp * (1 + 0.16 * waveIndex)`
    // formula continues to bite past wave 7 for free.
    private var endless = false

    private val lock = Any()
    private val enemies = ArrayList<Enemy>()
    private val towers = ArrayList<Tower>()
    private val projectiles = ArrayList<Projectile>()
    private val effects = ArrayList<Effect>()
    private val popups = ArrayList<Popup>()

    private var spawnQueue = ArrayList<Spawn>()
    private var spawnTimer = 0f

    // Selection / panels
    private var selectedPad = -1     // pad index tapped, -1 none
    private var showBuildPanel = false
    private var showUpgradePanel = false

    // ---- Rendering ------------------------------------------------------------------------
    private val assets = GameAssets(context)
    private var thread: Thread? = null
    @Volatile private var running = false

    private var viewW = 0
    private var viewH = 0

    private val pathPts = ArrayList<PointF>()
    private val segLen = ArrayList<Float>()
    private var totalLen = 0f
    private val pads = ArrayList<PointF>()
    private var padRadius = 0f
    private var coreX = 0f
    private var coreY = 0f

    // UI rects
    private val pauseBtn = RectF()
    private val startBtn = RectF()
    private val speedBtn = RectF()
    private val buildCells = Array(4) { RectF() }
    private val buildPanel = RectF()
    private val cancelBtn = RectF()
    private val upgradeBtn = RectF()
    private val sellBtn = RectF()
    private val overlayBtn1 = RectF()
    private val overlayBtn2 = RectF()
    private val overlayBtn3 = RectF()
    private val resumeBtn = RectF()
    private val quitBtn = RectF()

    private var gameSpeed = 1f

    // Paints
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    private val matrix = Matrix()

    var onExit: (() -> Unit)? = null

    init {
        holder.addCallback(this)
        isFocusable = true
        Sfx.init(context)
    }

    // ---- Entities -------------------------------------------------------------------------
    private inner class Enemy(val type: Int, waveIndex: Int) {
        val maxHp = enemyBaseHp[type] * (1f + 0.16f * waveIndex)
        var hp = maxHp
        var dist = 0f
        var x = 0f
        var y = 0f
        var slowTimer = 0f
        var slowFactor = 1f
        var alive = true
        val speed get() = viewH * 0.11f * enemySpeedFactor[type]
    }

    private inner class Tower(val padIndex: Int, val type: Int) {
        var level = 1
        var cooldown = 0f
        val x get() = pads[padIndex].x
        val y get() = pads[padIndex].y
        val damage get() = towerBaseDamage[type] * (1f + 0.4f * (level - 1))
        val range get() = viewW * towerRangeFactor[type] * (1f + 0.18f * (level - 1))
        val rate get() = towerFireRate[type] * (1f + 0.08f * (level - 1))
        fun upgradeCost() = (towerCost[type] * (0.8f + 0.6f * level)).toInt()
        fun sellValue() = (towerCost[type] * (0.5f + 0.35f * (level - 1))).toInt()
    }

    private inner class Projectile(
        var x: Float, var y: Float, val target: Enemy,
        val damage: Float, val color: Int, val towerType: Int
    ) {
        var dead = false
        val speed = viewH * 0.9f
    }

    private inner class Effect(var x: Float, var y: Float, val color: Int, val maxR: Float) {
        var t = 0f
        val dur = 0.35f
    }

    /**
     * Short-lived floating text drawn above an enemy at the moment it
     * dies — carries the crystal reward that landed. Purely visual;
     * has no gameplay side effects. Held in [popups] and cleared when
     * [t] exceeds [dur]; the label drifts upward while fading out.
     */
    private inner class Popup(
        val startX: Float,
        val startY: Float,
        val text: String,
        val color: Int,
    ) {
        var t = 0f
        val dur = 0.85f
    }

    private class Spawn(val type: Int, var delay: Float)

    // ---- Surface lifecycle ----------------------------------------------------------------
    override fun surfaceCreated(holder: SurfaceHolder) {
        running = true
        thread = Thread(this).also { it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        layout(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        try { thread?.join() } catch (_: InterruptedException) {}
        thread = null
    }

    fun pauseGame() { paused = true }
    fun resumeGameLoop() { paused = false }

    private fun layout(w: Int, h: Int) {
        viewW = w; viewH = h
        // Winding path in normalized coordinates.
        val norm = arrayOf(
            0.16f to -0.06f,
            0.16f to 0.17f,
            0.84f to 0.17f,
            0.84f to 0.38f,
            0.16f to 0.38f,
            0.16f to 0.585f,
            0.84f to 0.585f,
            0.84f to 0.78f,
            0.50f to 0.78f,
            0.50f to 0.90f
        )
        pathPts.clear()
        for ((nx, ny) in norm) pathPts.add(PointF(nx * w, ny * h))
        segLen.clear(); totalLen = 0f
        for (i in 0 until pathPts.size - 1) {
            val d = hypot(
                (pathPts[i + 1].x - pathPts[i].x).toDouble(),
                (pathPts[i + 1].y - pathPts[i].y).toDouble()
            ).toFloat()
            segLen.add(d); totalLen += d
        }
        coreX = pathPts.last().x
        coreY = pathPts.last().y

        pads.clear()
        val padNorm = arrayOf(
            0.50f to 0.075f,
            0.45f to 0.275f, 0.63f to 0.275f,
            0.35f to 0.485f, 0.52f to 0.485f, 0.685f to 0.485f,
            0.45f to 0.685f, 0.63f to 0.685f
        )
        for ((nx, ny) in padNorm) pads.add(PointF(nx * w, ny * h))
        padRadius = w * 0.072f

        // UI layout
        val m = 12f.dp
        val topH = 58f.dp
        pauseBtn.set(w - m - 46f.dp, m + topH, w - m, m + topH + 46f.dp)
        speedBtn.set(w - m - 46f.dp, pauseBtn.bottom + 10f.dp, w - m, pauseBtn.bottom + 10f.dp + 46f.dp)
        val sbw = 190f.dp; val sbh = 62f.dp
        startBtn.set((w - sbw) / 2f, h - sbh - 26f.dp, (w + sbw) / 2f, h - 26f.dp)

        // Build panel (bottom sheet, 4 cells + cancel)
        val bpH = 192f.dp
        buildPanel.set(m, h - bpH - m, w - m, h - m)
        val cellW = (buildPanel.width() - 5 * 10f.dp) / 4f
        val cellTop = buildPanel.top + 44f.dp
        val cellBottom = buildPanel.bottom - 14f.dp
        for (i in 0 until 4) {
            val left = buildPanel.left + 10f.dp + i * (cellW + 10f.dp)
            buildCells[i].set(left, cellTop, left + cellW, cellBottom)
        }
        cancelBtn.set(buildPanel.right - 40f.dp, buildPanel.top + 8f.dp, buildPanel.right - 8f.dp, buildPanel.top + 40f.dp)

        val ubw = (buildPanel.width() - 3 * 12f.dp) / 2f
        val upgTop = buildPanel.top + 60f.dp
        upgradeBtn.set(buildPanel.left + 12f.dp, upgTop, buildPanel.left + 12f.dp + ubw, cellBottom)
        sellBtn.set(upgradeBtn.right + 12f.dp, upgTop, upgradeBtn.right + 12f.dp + ubw, cellBottom)

        // Overlay buttons. Three slots so the VICTORY overlay can offer
        // ENDLESS / RESTART / MENU; the DEFEAT overlay only uses the top
        // two (RETRY / MENU) so btn3 stays hidden on that branch.
        val obw = 200f.dp; val obh = 60f.dp
        val obGap = 14f.dp
        val obTop = h * 0.55f
        overlayBtn1.set((w - obw) / 2f, obTop, (w + obw) / 2f, obTop + obh)
        overlayBtn2.set((w - obw) / 2f, obTop + obh + obGap, (w + obw) / 2f, obTop + 2 * obh + obGap)
        overlayBtn3.set((w - obw) / 2f, obTop + 2 * (obh + obGap), (w + obw) / 2f, obTop + 3 * obh + 2 * obGap)

        // Pause overlay buttons
        resumeBtn.set((w - obw) / 2f, h * 0.42f, (w + obw) / 2f, h * 0.42f + obh)
        quitBtn.set((w - obw) / 2f, h * 0.42f + obh + 16f.dp, (w + obw) / 2f, h * 0.42f + 2 * obh + 16f.dp)

        if (wave == 0 && enemies.isEmpty() && towers.isEmpty() && phase == Phase.PREP) {
            // fresh
        }
    }

    // ---- Path helpers ---------------------------------------------------------------------
    private fun posAt(dist: Float, out: PointF) {
        if (pathPts.isEmpty()) { out.set(0f, 0f); return }
        var d = dist
        for (i in segLen.indices) {
            if (d <= segLen[i]) {
                val t = if (segLen[i] == 0f) 0f else d / segLen[i]
                out.x = pathPts[i].x + (pathPts[i + 1].x - pathPts[i].x) * t
                out.y = pathPts[i].y + (pathPts[i + 1].y - pathPts[i].y) * t
                return
            }
            d -= segLen[i]
        }
        out.set(pathPts.last().x, pathPts.last().y)
    }

    // ---- Waves ----------------------------------------------------------------------------
    private fun buildWave(index: Int): ArrayList<Spawn> {
        val list = ArrayList<Spawn>()
        val base = 6 + index * 2
        fun add(type: Int, count: Int, gap: Float, lead: Float = 0f) {
            var first = true
            repeat(count) {
                list.add(Spawn(type, if (first) lead else gap).also { first = false })
            }
        }
        when (index) {
            0 -> add(0, base, 0.9f, 0.5f)
            1 -> { add(0, base, 0.8f, 0.5f); add(1, 3, 0.6f) }
            2 -> { add(1, 6, 0.6f, 0.5f); add(0, base, 0.7f) }
            3 -> { add(0, base, 0.6f, 0.5f); add(2, 3, 1.2f) }
            4 -> { add(1, 8, 0.5f, 0.5f); add(2, 4, 1.0f) }
            5 -> { add(0, 10, 0.5f, 0.5f); add(1, 8, 0.4f); add(2, 4, 1.0f) }
            6 -> { add(2, 6, 0.9f, 0.5f); add(1, 10, 0.4f); add(3, 1, 1.5f) }
            7 -> { add(0, 12, 0.4f, 0.5f); add(1, 12, 0.35f); add(2, 6, 0.8f); add(3, 2, 2.0f) }
            else -> {
                // Endless composition. `tier` grows with each post-campaign
                // wave and drives both the enemy count and the interval
                // compression. HP per enemy already scales for free through
                // `enemyBaseHp * (1 + 0.16 * waveIndex)`, so we don't need
                // to touch that side. Every 3rd endless wave rolls an extra
                // Circus Monster boss on top of the standard cocktail — a
                // predictable "boss cadence" reads more skill-testing than a
                // uniform ramp.
                val tier = index - 7
                val fastGap = (0.35f - 0.01f * tier).coerceAtLeast(0.16f)
                val slowGap = (0.85f - 0.02f * tier).coerceAtLeast(0.32f)
                val bossGap = (1.9f - 0.05f * tier).coerceAtLeast(0.9f)
                add(0, 12 + tier * 2, fastGap, 0.5f)
                add(1, 12 + tier * 2, fastGap - 0.05f)
                add(2, 6 + tier, slowGap)
                add(3, 2 + tier / 3, bossGap)
            }
        }
        return list
    }

    private fun startWave() {
        if (phase != Phase.PREP) return
        spawnQueue = buildWave(wave)
        spawnTimer = 0f
        phase = Phase.RUNNING
        Sfx.play(Sfx.SPAWN)
        if (wave == 0 && !endless) Analytics.gameStart()
    }

    // ---- Game loop ------------------------------------------------------------------------
    override fun run() {
        var last = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            var dt = (now - last) / 1_000_000_000f
            last = now
            if (dt > 0.05f) dt = 0.05f

            if (!paused) {
                synchronized(lock) { update(dt * gameSpeed) }
            }

            val canvas = holder.lockCanvas() ?: continue
            try {
                synchronized(lock) { renderGame(canvas) }
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }

    private val tmp = PointF()

    private fun update(dt: Float) {
        if (phase != Phase.RUNNING) return

        // Spawn
        if (spawnQueue.isNotEmpty()) {
            spawnTimer -= dt
            if (spawnTimer <= 0f) {
                val s = spawnQueue.removeAt(0)
                val e = Enemy(s.type, wave)
                enemies.add(e)
                if (s.type == 3) Sfx.play(Sfx.BOSS)
                spawnTimer = if (spawnQueue.isNotEmpty()) spawnQueue[0].delay else 0f
            }
        }

        // Enemies
        val iter = enemies.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            var sp = e.speed
            if (e.slowTimer > 0f) { e.slowTimer -= dt; sp *= e.slowFactor }
            e.dist += sp * dt
            if (e.dist >= totalLen) {
                lives -= enemyDamage[e.type]
                Sfx.play(Sfx.HIT, 0.7f)
                iter.remove()
                if (lives <= 0) {
                    lives = 0
                    phase = Phase.LOST
                    Sfx.play(Sfx.LOSE)
                    Analytics.gameLose(wave + 1, endless)
                }
                continue
            }
            posAt(e.dist, tmp); e.x = tmp.x; e.y = tmp.y
        }

        // Towers
        for (t in towers) {
            if (t.cooldown > 0f) t.cooldown -= dt
            if (t.cooldown <= 0f) {
                val target = pickTarget(t)
                if (target != null) {
                    projectiles.add(Projectile(t.x, t.y - padRadius * 0.4f, target, t.damage, towerColors[t.type], t.type))
                    t.cooldown = 1f / t.rate
                    Sfx.play(Sfx.SHOOT, 0.35f)
                }
            }
        }

        // Projectiles
        val pit = projectiles.iterator()
        while (pit.hasNext()) {
            val p = pit.next()
            if (!p.target.alive) { pit.remove(); continue }
            val dx = p.target.x - p.x
            val dy = p.target.y - p.y
            val d = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            val step = p.speed * dt
            if (d <= step + padRadius * 0.35f) {
                applyHit(p)
                pit.remove()
            } else {
                p.x += dx / d * step
                p.y += dy / d * step
            }
        }

        // Effects
        val eit = effects.iterator()
        while (eit.hasNext()) {
            val ef = eit.next(); ef.t += dt
            if (ef.t >= ef.dur) eit.remove()
        }

        // Floating reward popups
        val pop = popups.iterator()
        while (pop.hasNext()) {
            val pu = pop.next(); pu.t += dt
            if (pu.t >= pu.dur) pop.remove()
        }

        // Wave complete?
        if (phase == Phase.RUNNING && spawnQueue.isEmpty() && enemies.isEmpty()) {
            val bonus = 40 + wave * 12
            crystals += bonus
            Sfx.play(Sfx.COIN)
            // Endless mode never triggers the WON overlay — the run only
            // ends when the core falls. In scripted mode, clearing the
            // last wave is a genuine victory (offers ENDLESS from there).
            if (!endless && wave >= TOTAL_WAVES - 1) {
                phase = Phase.WON
                Sfx.play(Sfx.WIN)
                Analytics.gameWin(wave + 1)
            } else {
                wave++
                phase = Phase.PREP
                Sfx.play(Sfx.WIN, 0.5f)
            }
        }
    }

    private fun pickTarget(t: Tower): Enemy? {
        var best: Enemy? = null
        var bestDist = -1f
        val r = t.range
        for (e in enemies) {
            if (!e.alive) continue
            val d = hypot((e.x - t.x).toDouble(), (e.y - t.y).toDouble()).toFloat()
            if (d <= r && e.dist > bestDist) { best = e; bestDist = e.dist }
        }
        return best
    }

    private fun applyHit(p: Projectile) {
        val hitColor = p.color
        effects.add(Effect(p.target.x, p.target.y, hitColor, padRadius * 0.9f))
        damageEnemy(p.target, p.damage)
        when (p.towerType) {
            0 -> { // Fire: splash
                val splashR = viewW * 0.10f
                for (e in enemies) {
                    if (e === p.target || !e.alive) continue
                    val d = hypot((e.x - p.target.x).toDouble(), (e.y - p.target.y).toDouble()).toFloat()
                    if (d <= splashR) damageEnemy(e, p.damage * 0.5f)
                }
            }
            1 -> { // Frost: slow
                p.target.slowTimer = 1.6f
                p.target.slowFactor = 0.5f
            }
        }
    }

    private fun damageEnemy(e: Enemy, dmg: Float) {
        if (!e.alive) return
        e.hp -= dmg
        if (e.hp <= 0f) {
            e.alive = false
            val reward = enemyReward[e.type]
            crystals += reward
            // The floating reward label spawns slightly above the enemy
            // sprite so it doesn't clip into the HP bar below the head.
            popups.add(Popup(
                startX = e.x,
                startY = e.y - viewW * enemySizeFactor[e.type] * 0.5f,
                text = "+$reward",
                color = Color.parseColor("#FFB8F0FF"),
            ))
            enemies.remove(e)
        }
    }

    // ---- Drawing --------------------------------------------------------------------------
    private fun renderGame(canvas: Canvas) {
        drawBackground(canvas)
        drawPath(canvas)
        drawCore(canvas)
        drawPads(canvas)
        drawTowers(canvas)
        drawEnemies(canvas)
        drawProjectiles(canvas)
        drawEffects(canvas)
        drawPopups(canvas)
        drawHud(canvas)

        when (phase) {
            Phase.PREP -> drawStartButton(canvas)
            Phase.RUNNING -> {}
            Phase.WON -> drawOverlay(canvas, "VICTORY!", assets.victoryBg, Color.parseColor("#FFE08A"))
            Phase.LOST -> drawOverlay(canvas, "DEFEAT", assets.defeatBg, Color.parseColor("#FF6B6B"))
        }

        if (showBuildPanel) drawBuildPanel(canvas)
        if (showUpgradePanel) drawUpgradePanel(canvas)
        if (paused && phase != Phase.WON && phase != Phase.LOST) drawPauseOverlay(canvas)
    }

    private fun drawBackground(canvas: Canvas) {
        val bg = assets.gameBg
        if (bg != null) {
            drawCoverBitmap(canvas, bg, 0f, 0f, viewW.toFloat(), viewH.toFloat())
            fill.color = Color.parseColor("#66120616"); fill.style = Paint.Style.FILL
            canvas.drawRect(0f, 0f, viewW.toFloat(), viewH.toFloat(), fill)
        } else {
            canvas.drawColor(Color.parseColor("#1A0A24"))
        }
    }

    private fun drawPath(canvas: Canvas) {
        if (pathPts.size < 2) return
        val path = Path()
        path.moveTo(pathPts[0].x, pathPts[0].y)
        for (i in 1 until pathPts.size) path.lineTo(pathPts[i].x, pathPts[i].y)

        pathPaint.pathEffect = null
        pathPaint.strokeWidth = padRadius * 1.7f
        pathPaint.color = Color.parseColor("#661A0A24")
        canvas.drawPath(path, pathPaint)

        pathPaint.strokeWidth = padRadius * 1.35f
        pathPaint.color = Color.parseColor("#AA3A1C5A")
        canvas.drawPath(path, pathPaint)

        pathPaint.strokeWidth = 3f.dp
        pathPaint.color = Color.parseColor("#88F6C13A")
        pathPaint.pathEffect = DashPathEffect(floatArrayOf(14f.dp, 12f.dp), 0f)
        canvas.drawPath(path, pathPaint)
        pathPaint.pathEffect = null
    }

    private fun drawCore(canvas: Canvas) {
        val core = assets.core
        val size = viewW * 0.42f
        if (core != null) {
            drawBitmapCentered(canvas, core, coreX, coreY, size)
        } else {
            fill.color = Color.parseColor("#D2A93B"); fill.style = Paint.Style.FILL
            canvas.drawCircle(coreX, coreY, size * 0.4f, fill)
        }
    }

    private fun drawPads(canvas: Canvas) {
        for (i in pads.indices) {
            val hasTower = towers.any { it.padIndex == i }
            if (hasTower) continue
            val p = pads[i]
            stroke.color = Color.parseColor("#88F6C13A")
            stroke.strokeWidth = 2.5f.dp
            stroke.pathEffect = DashPathEffect(floatArrayOf(9f.dp, 7f.dp), 0f)
            canvas.drawCircle(p.x, p.y, padRadius * 0.82f, stroke)
            stroke.pathEffect = null
            fill.color = Color.parseColor("#33F6C13A")
            textPaint.color = Color.parseColor("#CCFFE08A")
            textPaint.textSize = padRadius * 0.9f
            canvas.drawText("+", p.x, p.y + textPaint.textSize * 0.35f, textPaint)
        }
    }

    private fun drawTowers(canvas: Canvas) {
        val base = assets.base
        for (t in towers) {
            // Base pedestal and orb are both centered exactly on the pad frame.
            if (base != null) drawBitmapContain(canvas, base, t.x, t.y, padRadius * 1.95f, padRadius * 1.95f)
            val orb = assets.towerBitmap(t.type)
            if (orb != null) drawBitmapContain(canvas, orb, t.x, t.y, padRadius * 1.15f, padRadius * 1.15f)
            // level pips centered under the tower
            fill.color = towerColors[t.type]; fill.style = Paint.Style.FILL
            val pipGap = 9f.dp
            val startX = t.x - (t.level - 1) * pipGap / 2f
            for (l in 0 until t.level) {
                canvas.drawCircle(startX + l * pipGap, t.y + padRadius * 0.98f, 3.5f.dp, fill)
            }
        }
        // range ring for selected tower
        if (showUpgradePanel && selectedPad >= 0) {
            val t = towers.firstOrNull { it.padIndex == selectedPad }
            if (t != null) {
                stroke.color = Color.parseColor("#55FFE08A"); stroke.strokeWidth = 2f.dp
                canvas.drawCircle(t.x, t.y, t.range, stroke)
            }
        }
    }

    private fun drawEnemies(canvas: Canvas) {
        for (e in enemies) {
            val bmp = assets.enemyBitmap(e.type)
            val size = viewW * enemySizeFactor[e.type]
            if (bmp != null) {
                if (e.slowTimer > 0f) bmpPaint.setColorFilterTint(Color.parseColor("#8840C0FF"))
                drawBitmapCentered(canvas, bmp, e.x, e.y, size)
                if (e.slowTimer > 0f) bmpPaint.colorFilter = null
            } else {
                fill.color = Color.RED; canvas.drawCircle(e.x, e.y, size * 0.4f, fill)
            }
            // HP bar
            val bw = size * 0.7f
            val by = e.y - size * 0.55f
            fill.color = Color.parseColor("#AA000000")
            canvas.drawRect(e.x - bw / 2, by, e.x + bw / 2, by + 5f.dp, fill)
            val frac = (e.hp / e.maxHp).coerceIn(0f, 1f)
            fill.color = when {
                frac > 0.5f -> Color.parseColor("#54D66A")
                frac > 0.25f -> Color.parseColor("#F6C13A")
                else -> Color.parseColor("#E5462F")
            }
            canvas.drawRect(e.x - bw / 2, by, e.x - bw / 2 + bw * frac, by + 5f.dp, fill)
        }
    }

    private fun drawProjectiles(canvas: Canvas) {
        for (p in projectiles) {
            fill.style = Paint.Style.FILL
            fill.color = p.color
            canvas.drawCircle(p.x, p.y, 7f.dp, fill)
            fill.color = Color.parseColor("#66FFFFFF")
            canvas.drawCircle(p.x, p.y, 3.5f.dp, fill)
        }
    }

    private fun drawEffects(canvas: Canvas) {
        for (ef in effects) {
            val f = ef.t / ef.dur
            val r = ef.maxR * f
            stroke.color = withAlpha(ef.color, (1f - f))
            stroke.strokeWidth = 4f.dp
            canvas.drawCircle(ef.x, ef.y, r, stroke)
        }
    }

    /**
     * Renders the floating '+N' reward labels. Each popup starts at the
     * enemy's death position and drifts up ~28.dp over its lifetime
     * while fading out on a decelerating curve — same shape the pilot
     * uses for its progress bar so the two on-screen motions read as
     * intentional siblings.
     */
    private fun drawPopups(canvas: Canvas) {
        if (popups.isEmpty()) return
        val prevAlign = textPaint.textAlign
        val prevSize = textPaint.textSize
        val prevColor = textPaint.color
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 16f.dp
        for (pu in popups) {
            val f = (pu.t / pu.dur).coerceIn(0f, 1f)
            val ease = 1f - (1f - f) * (1f - f) // ease-out quad
            val y = pu.startY - 28f.dp * ease
            val alpha = 1f - f
            textPaint.color = withAlpha(pu.color, alpha)
            textPaint.setShadowLayer(6f, 0f, 2f, withAlpha(Color.BLACK, alpha * 0.65f))
            canvas.drawText(pu.text, pu.startX, y, textPaint)
        }
        textPaint.clearShadowLayer()
        textPaint.textAlign = prevAlign
        textPaint.textSize = prevSize
        textPaint.color = prevColor
    }

    private fun drawHud(canvas: Canvas) {
        val top = 10f.dp
        val h = 52f.dp
        fill.color = Color.parseColor("#B3160A22"); fill.style = Paint.Style.FILL
        canvas.drawRoundRect(10f.dp, top, viewW - 10f.dp, top + h, 16f.dp, 16f.dp, fill)

        // Crystals
        val crystal = assets.crystal
        val iconY = top + h / 2f
        if (crystal != null) drawBitmapCentered(canvas, crystal, 40f.dp, iconY, 40f.dp)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = Color.parseColor("#B8F0FF")
        textPaint.textSize = 20f.dp
        canvas.drawText("$crystals", 62f.dp, iconY + 7f.dp, textPaint)

        // Lives (heart)
        drawHeart(canvas, viewW / 2f - 34f.dp, iconY, 13f.dp)
        textPaint.color = Color.parseColor("#FF9E9E")
        canvas.drawText("$lives", viewW / 2f - 14f.dp, iconY + 7f.dp, textPaint)

        // Wave
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.color = Color.parseColor("#FFE08A")
        val waveLabel = if (endless) "Wave ${wave + 1}  \u221E" else "Wave ${wave + 1}/$TOTAL_WAVES"
        canvas.drawText(waveLabel, viewW - 62f.dp, iconY + 7f.dp, textPaint)
        textPaint.textAlign = Paint.Align.CENTER

        // Pause + speed buttons
        drawIconButton(canvas, pauseBtn, if (paused) "\u25B6" else "II")
        drawIconButton(canvas, speedBtn, "x${gameSpeed.toInt()}")
    }

    private fun drawStartButton(canvas: Canvas) {
        drawPillButton(canvas, startBtn, "START WAVE ${wave + 1}",
            Color.parseColor("#FFF6C13A"), Color.parseColor("#2A0E3F"))
    }

    private fun drawBuildPanel(canvas: Canvas) {
        fill.color = Color.parseColor("#EE160A22"); fill.style = Paint.Style.FILL
        canvas.drawRoundRect(buildPanel, 20f.dp, 20f.dp, fill)
        stroke.color = Color.parseColor("#88F6C13A"); stroke.strokeWidth = 2f.dp
        canvas.drawRoundRect(buildPanel, 20f.dp, 20f.dp, stroke)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = Color.parseColor("#FFE08A"); textPaint.textSize = 16f.dp
        canvas.drawText("Place an artist", buildPanel.left + 16f.dp, buildPanel.top + 28f.dp, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
        drawIconButton(canvas, cancelBtn, "\u2715")

        for (i in 0 until 4) {
            val c = buildCells[i]
            val afford = crystals >= towerCost[i]
            fill.color = if (afford) Color.parseColor("#552A0E3F") else Color.parseColor("#55401515")
            canvas.drawRoundRect(c, 12f.dp, 12f.dp, fill)
            stroke.color = towerColors[i]; stroke.strokeWidth = 2f.dp
            canvas.drawRoundRect(c, 12f.dp, 12f.dp, stroke)

            // Orb centered in a uniform square box at the top of the cell.
            val boxSize = minOf(c.width() * 0.86f, c.height() * 0.46f)
            val orbCy = c.top + 8f.dp + boxSize / 2f
            val orb = assets.towerBitmap(i)
            if (orb != null) drawBitmapContain(canvas, orb, c.centerX(), orbCy, boxSize, boxSize)

            textPaint.textAlign = Paint.Align.CENTER
            // Name (auto-shrink so it never spills past the cell borders)
            textPaint.color = Color.parseColor("#FFE08A")
            val nameMax = c.width() - 12f.dp
            textPaint.textSize = 12f.dp
            val nameW = textPaint.measureText(towerNames[i])
            if (nameW > nameMax) textPaint.textSize = 12f.dp * (nameMax / nameW)
            canvas.drawText(towerNames[i], c.centerX(), orbCy + boxSize / 2f + 16f.dp, textPaint)
            // Role / purpose
            textPaint.textSize = 10.5f.dp; textPaint.color = Color.parseColor("#B9A6DD")
            canvas.drawText(towerRole[i], c.centerX(), orbCy + boxSize / 2f + 32f.dp, textPaint)
            // Cost
            textPaint.textSize = 13f.dp
            textPaint.color = if (afford) Color.parseColor("#B8F0FF") else Color.parseColor("#FF8A8A")
            canvas.drawText("\u25C6 ${towerCost[i]}", c.centerX(), c.bottom - 10f.dp, textPaint)
        }
    }

    private fun drawUpgradePanel(canvas: Canvas) {
        val t = towers.firstOrNull { it.padIndex == selectedPad } ?: run { showUpgradePanel = false; return }
        fill.color = Color.parseColor("#EE160A22"); fill.style = Paint.Style.FILL
        canvas.drawRoundRect(buildPanel, 20f.dp, 20f.dp, fill)
        stroke.color = Color.parseColor("#88F6C13A"); stroke.strokeWidth = 2f.dp
        canvas.drawRoundRect(buildPanel, 20f.dp, 20f.dp, stroke)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = Color.parseColor("#FFE08A"); textPaint.textSize = 16f.dp
        canvas.drawText("${towerNames[t.type]}  •  Lv ${t.level}", buildPanel.left + 16f.dp, buildPanel.top + 26f.dp, textPaint)
        textPaint.color = Color.parseColor("#B9A6DD"); textPaint.textSize = 12.5f.dp
        canvas.drawText("${towerRole[t.type]}  •  DMG ${t.damage.toInt()}", buildPanel.left + 16f.dp, buildPanel.top + 44f.dp, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
        drawIconButton(canvas, cancelBtn, "\u2715")

        val maxed = t.level >= 3
        val upCost = t.upgradeCost()
        val canUp = !maxed && crystals >= upCost
        fill.color = if (canUp) Color.parseColor("#552A5A2A") else Color.parseColor("#55333333")
        canvas.drawRoundRect(upgradeBtn, 12f.dp, 12f.dp, fill)
        stroke.color = Color.parseColor("#88F6C13A"); canvas.drawRoundRect(upgradeBtn, 12f.dp, 12f.dp, stroke)
        textPaint.color = Color.WHITE; textPaint.textSize = 15f.dp
        canvas.drawText(if (maxed) "MAX LEVEL" else "Upgrade", upgradeBtn.centerX(), upgradeBtn.centerY() - 4f.dp, textPaint)
        if (!maxed) {
            textPaint.textSize = 12f.dp; textPaint.color = Color.parseColor("#B8F0FF")
            canvas.drawText("$upCost", upgradeBtn.centerX(), upgradeBtn.centerY() + 16f.dp, textPaint)
        }

        fill.color = Color.parseColor("#55552020")
        canvas.drawRoundRect(sellBtn, 12f.dp, 12f.dp, fill)
        stroke.color = Color.parseColor("#88E5462F"); canvas.drawRoundRect(sellBtn, 12f.dp, 12f.dp, stroke)
        textPaint.color = Color.WHITE; textPaint.textSize = 15f.dp
        canvas.drawText("Sell", sellBtn.centerX(), sellBtn.centerY() - 4f.dp, textPaint)
        textPaint.textSize = 12f.dp; textPaint.color = Color.parseColor("#FFD08A")
        canvas.drawText("+${t.sellValue()}", sellBtn.centerX(), sellBtn.centerY() + 16f.dp, textPaint)
    }

    private fun drawOverlay(canvas: Canvas, title: String, bg: Bitmap?, titleColor: Int) {
        if (bg != null) {
            drawCoverBitmap(canvas, bg, 0f, 0f, viewW.toFloat(), viewH.toFloat())
        }
        fill.color = Color.parseColor("#B3000000"); fill.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, viewW.toFloat(), viewH.toFloat(), fill)

        textPaint.color = titleColor; textPaint.textSize = 46f.dp
        textPaint.setShadowLayer(14f, 0f, 4f, Color.BLACK)
        canvas.drawText(title, viewW / 2f, viewH * 0.38f, textPaint)
        textPaint.clearShadowLayer()
        textPaint.color = Color.WHITE; textPaint.textSize = 18f.dp
        val sub = when {
            phase == Phase.WON -> "The show is legendary!"
            endless -> "Endless run: Wave ${wave + 1} reached"
            else -> "The circus has fallen..."
        }
        canvas.drawText(sub, viewW / 2f, viewH * 0.38f + 34f.dp, textPaint)

        if (phase == Phase.WON) {
            // Three-button ladder: keep playing forever, replay campaign
            // from wave 1, or bail out to the main menu.
            drawPillButton(canvas, overlayBtn1, "ENDLESS",
                Color.parseColor("#FFF6C13A"), Color.parseColor("#2A0E3F"))
            drawPillButton(canvas, overlayBtn2, "RESTART",
                Color.parseColor("#552A0E3F"), Color.parseColor("#FFE08A"))
            drawPillButton(canvas, overlayBtn3, "MENU",
                Color.parseColor("#552A0E3F"), Color.parseColor("#FFE08A"))
        } else {
            drawPillButton(canvas, overlayBtn1, "RETRY",
                Color.parseColor("#FFF6C13A"), Color.parseColor("#2A0E3F"))
            drawPillButton(canvas, overlayBtn2, "MENU",
                Color.parseColor("#552A0E3F"), Color.parseColor("#FFE08A"))
        }
    }

    private fun drawPauseOverlay(canvas: Canvas) {
        fill.color = Color.parseColor("#CC120616"); fill.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, viewW.toFloat(), viewH.toFloat(), fill)
        textPaint.color = Color.parseColor("#FFE08A"); textPaint.textSize = 40f.dp
        canvas.drawText("PAUSED", viewW / 2f, viewH * 0.32f, textPaint)
        drawPillButton(canvas, resumeBtn, "RESUME", Color.parseColor("#FFF6C13A"), Color.parseColor("#2A0E3F"))
        drawPillButton(canvas, quitBtn, "QUIT TO MENU", Color.parseColor("#FFF6C13A"), Color.parseColor("#2A0E3F"))
    }

    // ---- Draw helpers ---------------------------------------------------------------------
    private fun drawPillButton(canvas: Canvas, r: RectF, text: String, bgColor: Int, textColor: Int) {
        fill.color = bgColor; fill.style = Paint.Style.FILL
        canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, fill)
        stroke.color = Color.parseColor("#66000000"); stroke.strokeWidth = 2f.dp
        canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, stroke)
        textPaint.color = textColor; textPaint.textSize = 20f.dp
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(text, r.centerX(), r.centerY() + 7f.dp, textPaint)
    }

    private fun drawIconButton(canvas: Canvas, r: RectF, text: String) {
        fill.color = Color.parseColor("#B3160A22"); fill.style = Paint.Style.FILL
        canvas.drawRoundRect(r, 12f.dp, 12f.dp, fill)
        stroke.color = Color.parseColor("#88F6C13A"); stroke.strokeWidth = 2f.dp
        canvas.drawRoundRect(r, 12f.dp, 12f.dp, stroke)
        textPaint.color = Color.parseColor("#FFE08A"); textPaint.textSize = 18f.dp
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(text, r.centerX(), r.centerY() + 6f.dp, textPaint)
    }

    private fun drawHeart(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        val path = Path()
        path.moveTo(cx, cy + s * 0.8f)
        path.cubicTo(cx - s * 1.6f, cy - s * 0.4f, cx - s * 0.6f, cy - s * 1.2f, cx, cy - s * 0.35f)
        path.cubicTo(cx + s * 0.6f, cy - s * 1.2f, cx + s * 1.6f, cy - s * 0.4f, cx, cy + s * 0.8f)
        fill.color = Color.parseColor("#E5462F"); fill.style = Paint.Style.FILL
        canvas.drawPath(path, fill)
    }

    private fun drawBitmapCentered(canvas: Canvas, bmp: Bitmap, cx: Float, cy: Float, targetW: Float) {
        val scale = targetW / bmp.width
        val h = bmp.height * scale
        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(cx - targetW / 2f, cy - h / 2f)
        canvas.drawBitmap(bmp, matrix, bmpPaint)
    }

    /** Draws [bmp] scaled to fit within a [boxW] x [boxH] box, centered at (cx, cy), preserving aspect. */
    private fun drawBitmapContain(canvas: Canvas, bmp: Bitmap, cx: Float, cy: Float, boxW: Float, boxH: Float) {
        val scale = minOf(boxW / bmp.width, boxH / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(cx - dw / 2f, cy - dh / 2f)
        canvas.drawBitmap(bmp, matrix, bmpPaint)
    }

    private fun drawCoverBitmap(canvas: Canvas, bmp: Bitmap, x: Float, y: Float, w: Float, h: Float) {
        val scale = maxOf(w / bmp.width, h / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(x + (w - dw) / 2f, y + (h - dh) / 2f)
        canvas.drawBitmap(bmp, matrix, bmpPaint)
    }

    private fun withAlpha(color: Int, factor: Float): Int {
        val a = (255 * factor).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    private fun Paint.setColorFilterTint(color: Int) {
        colorFilter = android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_ATOP)
    }

    // ---- Input ----------------------------------------------------------------------------
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return true
        val x = event.x; val y = event.y
        synchronized(lock) { handleTap(x, y) }
        return true
    }

    private fun handleTap(x: Float, y: Float) {
        // Overlays first
        if (phase == Phase.WON) {
            when {
                overlayBtn1.contains(x, y) -> { Sfx.play(Sfx.CLICK); startEndless() }
                overlayBtn2.contains(x, y) -> { Sfx.play(Sfx.CLICK); restart() }
                overlayBtn3.contains(x, y) -> { Sfx.play(Sfx.CLICK); onExit?.invoke() }
            }
            return
        }
        if (phase == Phase.LOST) {
            if (overlayBtn1.contains(x, y)) { Sfx.play(Sfx.CLICK); restart() }
            else if (overlayBtn2.contains(x, y)) { Sfx.play(Sfx.CLICK); onExit?.invoke() }
            return
        }
        if (paused) {
            if (resumeBtn.contains(x, y)) { Sfx.play(Sfx.CLICK); paused = false }
            else if (quitBtn.contains(x, y)) { Sfx.play(Sfx.CLICK); onExit?.invoke() }
            return
        }

        // Top buttons
        if (pauseBtn.contains(x, y)) { Sfx.play(Sfx.CLICK); paused = true; return }
        if (speedBtn.contains(x, y)) { Sfx.play(Sfx.CLICK); gameSpeed = if (gameSpeed >= 2f) 1f else 2f; return }

        // Build panel
        if (showBuildPanel) {
            if (cancelBtn.contains(x, y) || !buildPanel.contains(x, y)) { closePanels(); return }
            for (i in 0 until 4) {
                if (buildCells[i].contains(x, y)) { tryBuild(i); return }
            }
            return
        }
        // Upgrade panel
        if (showUpgradePanel) {
            if (cancelBtn.contains(x, y) || !buildPanel.contains(x, y)) { closePanels(); return }
            if (upgradeBtn.contains(x, y)) { tryUpgrade(); return }
            if (sellBtn.contains(x, y)) { trySell(); return }
            return
        }

        // Start wave
        if (phase == Phase.PREP && startBtn.contains(x, y)) { Sfx.play(Sfx.CLICK); startWave(); return }

        // Pads
        for (i in pads.indices) {
            val p = pads[i]
            if (hypot((x - p.x).toDouble(), (y - p.y).toDouble()) <= padRadius * 1.05f) {
                selectedPad = i
                if (towers.any { it.padIndex == i }) {
                    showUpgradePanel = true; showBuildPanel = false
                } else {
                    showBuildPanel = true; showUpgradePanel = false
                }
                Sfx.play(Sfx.CLICK)
                return
            }
        }
    }

    private fun closePanels() {
        showBuildPanel = false; showUpgradePanel = false; selectedPad = -1
    }

    private fun tryBuild(type: Int) {
        if (selectedPad < 0) return
        if (towers.any { it.padIndex == selectedPad }) return
        if (crystals < towerCost[type]) { Sfx.play(Sfx.HIT, 0.4f); return }
        crystals -= towerCost[type]
        towers.add(Tower(selectedPad, type))
        Sfx.play(Sfx.BUILD)
        closePanels()
    }

    private fun tryUpgrade() {
        val t = towers.firstOrNull { it.padIndex == selectedPad } ?: return
        if (t.level >= 3) return
        val cost = t.upgradeCost()
        if (crystals < cost) { Sfx.play(Sfx.HIT, 0.4f); return }
        crystals -= cost
        t.level++
        Sfx.play(Sfx.BUILD)
    }

    private fun trySell() {
        val t = towers.firstOrNull { it.padIndex == selectedPad } ?: return
        crystals += t.sellValue()
        towers.remove(t)
        Sfx.play(Sfx.COIN)
        closePanels()
    }

    private fun restart() {
        enemies.clear(); towers.clear(); projectiles.clear(); effects.clear(); popups.clear()
        spawnQueue.clear()
        crystals = START_CRYSTALS; lives = START_LIVES; wave = 0
        endless = false
        phase = Phase.PREP; paused = false
        closePanels()
    }

    /**
     * Enter endless mode from the VICTORY overlay. Keeps the player's
     * board (towers, crystals, remaining lives) as their well-earned
     * head start, flips the endless flag on, and hands control back to
     * PREP for the first post-campaign wave. From here on the wave
     * counter never wraps and the WON overlay can no longer fire —
     * only DEFEAT can end the run.
     */
    private fun startEndless() {
        endless = true
        wave++
        phase = Phase.PREP
        paused = false
        closePanels()
        Analytics.endlessStarted()
    }
}
