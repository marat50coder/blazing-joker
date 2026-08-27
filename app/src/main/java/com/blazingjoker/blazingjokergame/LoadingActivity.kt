package com.blazingjoker.blazingjokergame

import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.blazingjoker.blazingjokergame.link.LinkPilot
import com.blazingjoker.blazingjokergame.link.config.LinkConfig
import com.blazingjoker.blazingjokergame.link.data.Berth
import com.blazingjoker.blazingjokergame.link.data.LastCourse
import com.blazingjoker.blazingjokergame.link.push.HornFcmService
import com.blazingjoker.blazingjokergame.link.push.PushBus
import com.blazingjoker.blazingjokergame.link.ui.AlertOptInActivity
import com.blazingjoker.blazingjokergame.link.ui.NoLinkActivity
import com.blazingjoker.blazingjokergame.link.ui.WebCanvasActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Splash / loading screen AND boot dispatcher.
 *
 * On start we:
 *   1. Consume any cold-tap URL from the launching intent and stash it
 *      so the pilot picks it up on its first frame.
 *   2. Show the branded loading art + animated progress bar.
 *   3. Run [LinkPilot.chart] on IO. The pilot's progress callback
 *      raises the bar; the bar only ever reaches 100% right before
 *      the dispatch.
 *   4. Dispatch on the returned [Berth]:
 *        Native      -> MainMenuActivity  (the tower-defence game)
 *        Web         -> AlertOptInActivity or WebCanvasActivity, per
 *                       the stowage's `shouldInvitePermission`
 *        LostSignal  -> NoLinkActivity
 *
 * If credentials are not yet packed (fresh checkout), the pilot short-
 * circuits to Berth.Native and the user plays the game unaffected. This
 * means the game itself remains a self-contained, offline-capable app
 * even without a working backend.
 */
class LoadingActivity : AppCompatActivity() {

    private lateinit var background: ImageView
    private lateinit var progressBar: HorizontalProgressBar
    private lateinit var loadingText: TextView

    private val uiHandler = Handler(Looper.getMainLooper())
    private var dotCount = 0
    private val loaderScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var chartJob: Job? = null

    // Hold the Android 12+ system splash until the first-launch
    // referrer probe finishes so we can jump straight to NoLink
    // without painting the branded loading art first.
    @Volatile
    private var holdSystemSplash = false

    // Progress bar has three phases: base warm-up (0 -> ~0.15), pilot-driven
    // (~0.15 -> 0.95), then final flush before dispatch (0.95 -> 1.0). This
    // keeps the bar visibly alive even when the pilot resolves quickly.
    @Volatile
    private var pilotProgress: Float = 0f
    private var barAnimator: ValueAnimator? = null

    private val dotRunnable = object : Runnable {
        override fun run() {
            dotCount = (dotCount + 1) % 4
            loadingText.text = getString(R.string.loading) + ".".repeat(dotCount)
            uiHandler.postDelayed(this, 400L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { holdSystemSplash }
        super.onCreate(savedInstanceState)
        Ui.immersive(this)

        val pilot = LinkPilot.of(this)

        // CRITICAL: wire the AppsFlyer listeners up BEFORE any lifecycle
        // callback fires. The SDK uses ActivityLifecycleCallbacks to notice
        // the first onResume, and if init() runs after we're already
        // resumed, the launch event is queued to the next transition —
        // ~30 s later when the game menu opens — and the conversion
        // listener silently never fires. Registering here fixes that.
        pilot.wireUp()

        // Forward the launch intent so a OneLink VIEW-intent gets its
        // query params (media_source, campaign, deep_link_value, af_sub1…)
        // harvested up-front. The pilot merges those into the POST body
        // even when the SDK stays silent.
        pilot.notifyLaunchIntent(intent)

        val coldUrl = extractPushUrl(intent)
        if (coldUrl.isNotEmpty()) {
            Log.d(TAG, "cold-tap URL from launch intent: $coldUrl")

            // Warm hand-off: if the WebView shell is already on screen,
            // load the URL there directly instead of relaunching the
            // whole pilot. This is what turns a "loading → new page"
            // flash into a normal in-page navigation for pushes tapped
            // while the app is foregrounded (foollegends WelcomePortal
            // does the exact same thing).
            if (PushBus.shellAlive && PushBus.handOver(coldUrl)) {
                Log.d(TAG, "warm push handed to the live shell")
                finish()
                overridePendingTransition(0, 0)
                return
            }

            pilot.stowage.stashPendingUrl(coldUrl)
        }

        // On the VERY FIRST launch after install we must consult the
        // Google Play Install Referrer BEFORE any fast-path can commit
        // an offline berth. Without this, a OneLink install opened
        // before the device is online takes the "no carrier" fast path
        // straight into the white game, and a later launch (once
        // network is up) flips the same install into gray — the
        // reported "белая → серая" bug. The referrer is delivered
        // offline via binder IPC and carries the OneLink parameters,
        // so probing it here is our only offline channel to detect a
        // tracked click. Guarded by `referrerProbed` — we only pay
        // this cost once per install.
        if (!pilot.stowage.referrerProbed) {
            Log.d(TAG, "first launch: probing install referrer before dispatch")
            // Keep the system splash up — do NOT paint the branded
            // loading art yet. After the probe we know whether this
            // install is gray+offline (jump to NoLink with no bar) or
            // white / online (then we build the branded splash).
            holdSystemSplash = true
            pilot.kickOffAmbient()
            loaderScope.launch {
                withContext(Dispatchers.IO) { pilot.probeInstallReferrer() }
                decideAfterProbe()
            }
            return
        }

        // Fast path #1: last-known course is Native. Once we have
        // committed a user to the white-part game, every subsequent
        // launch MUST reach the menu even fully offline — the native
        // game itself has no network dependency, so making it wait on
        // attribution + chart POST just to potentially discover a Web
        // upgrade is what triggered the "second launch of the white
        // part needs internet" report.
        //
        // We still show the branded loading splash for ~1 s here so
        // the user never sees "app opens straight into the menu"
        // (which the tester reported as "loading screen disappeared
        // after the first launch"). Two shapes:
        //   * `attributedOrganic` latched → let the pilot run.
        //     [LinkPilot.chart] short-circuits to `Berth.Native` at
        //     the top with `onProgress(1f)` in <10 ms via the organic
        //     latch, so the pilot's own progress-bar animation plays
        //     out with zero network dependency.
        //   * organic latch NOT set (course=Native came from a prior
        //     server no-url reply, not from the referrer probe) →
        //     running the pilot would still block on decideReturningNative's
        //     awaitInstall. Show the splash on a timer and dispatch
        //     via [skipToOfflineBerth] as before.
        if (LinkConfig.credentialsReady &&
            coldUrl.isEmpty() &&
            pilot.stowage.course == LastCourse.Native
        ) {
            if (pilot.stowage.attributedOrganic) {
                Log.d(TAG, "fast-path: course=Native + organic latch → pilot for splash")
                buildScreen()
                pilot.kickOffAmbient()
                return
            }
            Log.d(TAG, "fast-path: course=Native → MainMenu with visible splash")
            buildScreen()
            scheduleSplashThen { if (!isFinishing) skipToOfflineBerth(pilot) }
            return
        }

        // Fast path #2: no USABLE internet. `hasValidatedInternet()` is
        // strictly stronger than `hasCarrier()` — it also requires
        // Android's own captive-portal probe to have succeeded, so the
        // "wifi on, no upstream" case (airport captive without login,
        // dead-ISP, expired mobile-data plan) trips this branch even
        // though a network transport is technically up. Without this,
        // the pilot runs on such a device and eats ~12 s on DNS probes
        // and ~26 s on AppsFlyer's install-wait before returning
        // LostSignal — the user just saw ~40 s of white loading for
        // nothing. From their seat, opening straight to the offline
        // berth reads the same but 40 s sooner.
        //
        // We DO NOT show the ~1 s loading splash when the offline berth
        // is going to route to the NoLink retry screen (gray user with
        // course=Web / course=Unset + non-organic latch): the tester
        // report calls out "loading progress appears then jumps to no-
        // wifi" as broken UX — they expect the no-wifi screen to appear
        // instantly the moment the app opens without a connection. The
        // splash stays only for the "would land on MainMenu" case (the
        // white game, whose loading transition is part of its own UX).
        if (LinkConfig.credentialsReady &&
            coldUrl.isEmpty() &&
            !pilot.auditor.hasValidatedInternet()
        ) {
            if (offlineDispatchIsMainMenu(pilot)) {
                Log.d(TAG, "fast-path: no internet → MainMenu with visible splash")
                buildScreen()
                scheduleSplashThen { if (!isFinishing) skipToOfflineBerth(pilot) }
            } else {
                Log.d(TAG, "fast-path: no internet → NoLink instantly (no splash)")
                skipToOfflineBerth(pilot)
            }
            return
        }

        buildScreen()
        pilot.kickOffAmbient()
    }

    /**
     * Mirrors the routing decision inside [skipToOfflineBerth]: returns
     * true when that method would open [MainMenuActivity], false when
     * it would open [NoLinkActivity]. Kept in sync with the `when`
     * ladder below.
     */
    private fun offlineDispatchIsMainMenu(pilot: LinkPilot): Boolean {
        val course = pilot.stowage.course
        val nonOrg = pilot.stowage.attributedNonOrganic
        val cached = pilot.stowage.cachedDestination().orEmpty().isNotEmpty()
        return when (course) {
            LastCourse.Native -> true
            LastCourse.Web -> false
            LastCourse.Unset -> !nonOrg && !cached
        }
    }

    /**
     * Drive the loading bar to 100 % over ~1 s and then invoke [action].
     * Used by the offline / already-committed fast paths so the user
     * always sees the splash even when we skip the pilot pipeline.
     */
    private fun scheduleSplashThen(action: () -> Unit) {
        driveBarTo(0.55f, 620L)
        uiHandler.postDelayed({
            driveBarTo(1f, 380L)
            uiHandler.postDelayed({ action() }, 260L)
        }, 680L)
    }

    /**
     * Post-probe branch: the install-referrer probe has finished (or
     * timed out) and any offline-detectable attribution has been
     * latched. The branded loading screen was NOT built yet — we only
     * paint it when the destination is the native game or when we
     * actually have internet and the pilot will run.
     *
     * Gray + no internet → NoLink immediately (no progress bar).
     * White + no internet → branded splash, then MainMenu.
     * Anything online → branded splash + full pilot.
     */
    private fun decideAfterProbe() {
        if (isFinishing) return
        val pilot = LinkPilot.of(this)
        val coldUrl = extractPushUrl(intent)

        if (LinkConfig.credentialsReady &&
            coldUrl.isEmpty() &&
            !pilot.auditor.hasValidatedInternet()
        ) {
            if (offlineDispatchIsMainMenu(pilot)) {
                Log.d(TAG, "post-probe: no internet → MainMenu with visible splash")
                holdSystemSplash = false
                buildScreen()
                scheduleSplashThen { if (!isFinishing) skipToOfflineBerth(pilot) }
            } else {
                Log.d(TAG, "post-probe: no internet → NoLink instantly (no splash)")
                skipToOfflineBerth(pilot)
            }
            return
        }

        holdSystemSplash = false
        buildScreen()
        pilot.kickOffAmbient()
        launchPilot()
    }

    /**
     * No carrier + credentials plumbed in → dispatch immediately based on
     * last-known course. Never blocks on I/O and never runs the pilot.
     *   Native → game (fully offline-capable)
     *   Web + cached URL → offline retry screen with the last URL for a resume load
     *   Web + no cache  → NoLink (nothing else to resume to)
     *   Unset + no cache → game (organic default; the white part has no
     *                     network dependency, so trapping the user on
     *                     NoLink here just means "opened white the first
     *                     time, second launch offline requires internet")
     *   Unset + cache   → NoLink (there is a pending Web URL to resume to)
     */
    private fun skipToOfflineBerth(pilot: LinkPilot) {
        holdSystemSplash = false
        val cached = pilot.stowage.cachedDestination().orEmpty()
        val nonOrg = pilot.stowage.attributedNonOrganic
        Log.d(
            TAG,
            "skipToOfflineBerth course=${pilot.stowage.course} " +
                "cachedNonEmpty=${cached.isNotEmpty()} nonOrganic=$nonOrg"
        )
        val next: Intent = when (pilot.stowage.course) {
            LastCourse.Native -> Intent(this, MainMenuActivity::class.java)
            LastCourse.Web -> Intent(this, NoLinkActivity::class.java).apply {
                if (cached.isNotEmpty()) {
                    putExtra(NoLinkActivity.EXTRA_RETRY_URL, cached)
                }
            }
            LastCourse.Unset -> when {
                // Attributed Non-organic user without a URL yet:
                // never white. Show NoLink so they can Retry once
                // connectivity comes back, and if by chance we DO
                // have a cached URL, hand it over for the resume.
                nonOrg -> Intent(this, NoLinkActivity::class.java).apply {
                    if (cached.isNotEmpty()) {
                        putExtra(NoLinkActivity.EXTRA_RETRY_URL, cached)
                    }
                }
                // Organic default: no evidence the user was ever
                // meant for the gray flow. Send them into the native
                // game so the offline relaunch is not blocked by a
                // NoLink screen.
                cached.isEmpty() -> Intent(this, MainMenuActivity::class.java)
                else -> Intent(this, NoLinkActivity::class.java).apply {
                    putExtra(NoLinkActivity.EXTRA_RETRY_URL, cached)
                }
            }
        }
        next.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(next)
        overridePendingTransition(0, 0)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val pilot = LinkPilot.of(this)
        pilot.notifyLaunchIntent(intent)
        val coldUrl = extractPushUrl(intent)
        if (coldUrl.isNotEmpty()) {
            Log.d(TAG, "cold-tap URL from onNewIntent: $coldUrl")

            // Warm hand-off wins over restarting the pilot: if a live
            // WebCanvas shell is still subscribed, deliver the URL to it
            // and close this LoadingActivity re-entry silently.
            if (PushBus.shellAlive && PushBus.handOver(coldUrl)) {
                Log.d(TAG, "warm push (onNewIntent) handed to the live shell")
                finish()
                return
            }

            // Push tapped while LoadingActivity is still on screen (rare —
            // usually CLEAR_TOP from the pending intent brings the shell
            // forward first). Stash the URL and RESTART the pilot so the
            // dispatcher gets a chance to route into WebCanvas with the
            // fresh URL. Without the restart the pilot job just holds the
            // previous verdict and the tap does nothing visible.
            pilot.stowage.stashPendingUrl(coldUrl)
            chartJob?.cancel()
            chartJob = null
            pilotProgress = 0f
            launchPilot()
        }
    }

    private fun buildScreen() {
        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#120616"))
        }

        background = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        root.addView(background)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28.dp, 18.dp, 28.dp, 22.dp)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 26f.dp
                setColor(Color.parseColor("#88000000"))
            }
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.bottomMargin = 64.dp
            lp.leftMargin = 28.dp
            lp.rightMargin = 28.dp
            layoutParams = lp
        }

        loadingText = TextView(this).apply {
            text = getString(R.string.loading)
            setTextColor(Color.parseColor("#FFFFE08A"))
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(10f, 0f, 3f, Color.parseColor("#CC000000"))
            gravity = Gravity.CENTER
        }
        panel.addView(
            loadingText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16.dp }
        )

        progressBar = HorizontalProgressBar(this)
        panel.addView(progressBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 24.dp
        ))

        root.addView(panel)
        setContentView(root)
        applyOrientationArt(resources.configuration)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientationArt(newConfig)
    }

    private fun applyOrientationArt(config: Configuration) {
        background.setImageResource(
            if (config.orientation == Configuration.ORIENTATION_LANDSCAPE)
                R.drawable.loading_horizontal
            else R.drawable.loading_vertical
        )
    }

    override fun onResume() {
        super.onResume()
        Ui.immersive(this)
        val pilot = LinkPilot.of(this)
        // AppsFlyer launch must be hosted by a real Activity even while
        // the first-launch referrer probe is still holding the system
        // splash (no branded UI yet).
        if (!isFinishing) pilot.start(this)
        if (isFinishing || !::progressBar.isInitialized) return

        uiHandler.post(dotRunnable)

        // Do not launch the pilot while the install-referrer probe is
        // still in flight (first launch after install). The probe
        // coroutine kicked off in onCreate owns the dispatch decision
        // in that window — running the pilot in parallel would reopen
        // the offline race the probe was added to close.
        if (!pilot.stowage.referrerProbed) return

        launchPilot()
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(dotRunnable)
    }

    override fun onDestroy() {
        chartJob?.cancel()
        barAnimator?.cancel()
        loaderScope.cancel()
        super.onDestroy()
    }

    private fun launchPilot() {
        if (chartJob != null) return

        // Warm-up: bar drifts to 12% while the pilot is spinning up so the
        // user never sees a static empty bar even if attribution stalls.
        driveBarTo(0.12f, 900L)

        chartJob = loaderScope.launch {
            val berth = withContext(Dispatchers.IO) {
                LinkPilot.of(this@LoadingActivity).chart { fraction ->
                    pilotProgress = fraction.coerceIn(0f, 0.95f)
                    // Post to UI thread — animator is UI-only.
                    uiHandler.post { driveBarTo(pilotProgress, 380L) }
                }
            }

            // Instant NoLink: if the pilot already knows we have no
            // usable internet, do not animate the bar to 100 % and
            // wait 260 ms — that is the "loading completes then no
            // wifi" flash the tester reported.
            val pilot = LinkPilot.of(this@LoadingActivity)
            if (berth is Berth.LostSignal && !pilot.auditor.hasValidatedInternet()) {
                dispatch(berth)
                return@launch
            }

            // Final flush — bar to 100% right before dispatch. This is the
            // "only in the final moment" fill mandated by the design brief.
            driveBarTo(1f, 380L)
            uiHandler.postDelayed({ dispatch(berth) }, 260L)
        }
    }

    private fun driveBarTo(target: Float, durationMs: Long) {
        val start = progressBar.progress
        val clamped = target.coerceIn(0f, 1f)
        if (clamped <= start) return
        barAnimator?.cancel()
        val anim = ValueAnimator.ofFloat(start, clamped).apply {
            duration = max(120L, durationMs)
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progressBar.progress = it.animatedValue as Float
            }
        }
        barAnimator = anim
        anim.start()
    }

    /**
     * Pull the URL to open out of the launch intent.
     *
     * Two very different paths land here:
     *   1. Our own [HornFcmService] built a PendingIntent whose extras
     *      contain [HornFcmService.EXTRA_COLD_TAP_URL] — that fires only
     *      when the app was foreground OR was fully cold with `data`-only
     *      pushes (Android calls our [FirebaseMessagingService]).
     *   2. Firebase Cloud Messaging delivered a payload that carried a
     *      `notification` block AND the app was in background: the SDK
     *      draws the tray entry ITSELF, our service is bypassed, and the
     *      tap opens the launcher with the `data` KEYS injected as raw
     *      string extras. In that case the URL sits at `url`/`link`/
     *      `deeplink` on the intent, NOT under our custom key.
     *
     * Checking all four keeps every path routing to the same page.
     */
    private fun extractPushUrl(intent: Intent?): String {
        if (intent == null) return ""
        Log.d(
            TAG,
            "extractPushUrl: action=${intent.action} data=${intent.data} " +
                "extras=${intent.extras?.keySet()?.joinToString()}",
        )

        val direct = intent.getStringExtra(HornFcmService.EXTRA_COLD_TAP_URL)
        if (!direct.isNullOrEmpty()) {
            Log.d(TAG, "extractPushUrl: from EXTRA_COLD_TAP_URL → $direct")
            return direct
        }

        val extras = intent.extras
        if (extras != null) {
            for (key in FCM_URL_KEYS) {
                val v = extras.getString(key) ?: continue
                if (v.isEmpty()) continue
                if (looksLikeHttp(v)) {
                    Log.d(TAG, "extractPushUrl: from extras[$key] → $v")
                    return v
                }
                Log.d(TAG, "extractPushUrl: extras[$key]=$v ignored (not http)")
            }
        }

        // Firebase's tray tap intent sometimes attaches the deep-link URI
        // as the intent DATA (VIEW action) rather than as an extras key —
        // depends on whether the console payload had a `notification.click_action`
        // set or a `data.url` alone.
        if (intent.action == Intent.ACTION_VIEW) {
            val fromUri = intent.data?.toString().orEmpty()
            if (fromUri.isNotEmpty() && looksLikeHttp(fromUri) && !isMarketingHost(fromUri)) {
                Log.d(TAG, "extractPushUrl: from intent.data → $fromUri")
                return fromUri
            }
        }
        Log.d(TAG, "extractPushUrl: no URL found")
        return ""
    }

    private fun isMarketingHost(url: String): Boolean {
        // OneLink / AppsFlyer VIEW intents are attribution launches, NOT
        // push taps — those should go through the pilot's attribution
        // path, not the cold-tap shortcut.
        val lower = url.lowercase()
        return lower.contains(".onelink.me") || lower.contains("app.appsflyer.com")
    }

    private fun looksLikeHttp(s: String): Boolean {
        val t = s.trim().lowercase()
        return t.startsWith("http://") || t.startsWith("https://")
    }

    private fun dispatch(berth: Berth) {
        val pilot = LinkPilot.of(this)
        val next: Intent = when (berth) {
            is Berth.Native -> {
                Log.d(TAG, "dispatch → Native (MainMenu)")
                Intent(this, MainMenuActivity::class.java)
            }

            is Berth.Web -> {
                val inviteWanted = pilot.stowage.shouldInvitePermission(this)
                Log.d(
                    TAG,
                    "dispatch → Web url=${berth.url} fromColdPush=${berth.fromColdPush} " +
                        "inviteWanted=$inviteWanted"
                )
                if (!berth.fromColdPush && inviteWanted) {
                    Intent(this, AlertOptInActivity::class.java).apply {
                        putExtra(AlertOptInActivity.EXTRA_DESTINATION_URL, berth.url)
                    }
                } else {
                    Intent(this, WebCanvasActivity::class.java).apply {
                        putExtra(WebCanvasActivity.EXTRA_URL, berth.url)
                    }
                }
            }

            is Berth.LostSignal -> {
                // Pilot returned LostSignal even though the carrier
                // may still be up — e.g. chart POST timed out, DNS
                // probe failed on a slow-cold-resolver, or a
                // captive-portal SSL bump broke the request. Three
                // routes out, in order of user promise:
                //
                //   (a) Web-committed OR attributed Non-organic user
                //       → NoLink (with the last cached URL for retry
                //       if we have one). Demoting them to the native
                //       game would silently break the gray promise —
                //       exactly the field report that led to this
                //       fix ("gray → time skip → white").
                //   (b) Truly organic user with a live carrier → the
                //       native game. Nothing to route to, and
                //       showing "no wifi" while wifi is on reads as
                //       broken.
                //   (c) No carrier → NoLink. The honest UX.
                val web = pilot.stowage.course == LastCourse.Web
                val nonOrg = pilot.stowage.attributedNonOrganic
                val cached = pilot.stowage.cachedDestination().orEmpty()
                if (web || nonOrg) {
                    Log.d(
                        TAG,
                        "dispatch → LostSignal + " +
                            (if (web) "course=Web " else "") +
                            (if (nonOrg) "nonOrganic " else "") +
                            "→ NoLink (retry)"
                    )
                    Intent(this, NoLinkActivity::class.java).apply {
                        if (cached.isNotEmpty()) {
                            putExtra(NoLinkActivity.EXTRA_RETRY_URL, cached)
                        }
                    }
                } else if (pilot.auditor.hasCarrier()) {
                    Log.d(TAG, "dispatch → LostSignal but carrier up (organic) → MainMenu")
                    Intent(this, MainMenuActivity::class.java)
                } else {
                    Log.d(TAG, "dispatch → LostSignal (NoLink)")
                    Intent(this, NoLinkActivity::class.java)
                }
            }
        }
        next.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(next)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    companion object {
        private const val TAG = "LoadingActivity"

        // FCM `notification`-block pushes bypass our FirebaseMessagingService
        // when the app is backgrounded — the data payload keys arrive as
        // raw intent extras on the launcher activity instead. These are
        // the conventional names sending tools use for the deep-link URL.
        private val FCM_URL_KEYS = arrayOf(
            "url", "link", "deeplink", "deep_link", "target_url", "web_url",
            "target", "landing",
        )
    }
}
