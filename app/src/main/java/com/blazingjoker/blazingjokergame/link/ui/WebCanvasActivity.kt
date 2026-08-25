package com.blazingjoker.blazingjokergame.link.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.blazingjoker.blazingjokergame.Ui
import com.blazingjoker.blazingjokergame.dp
import com.blazingjoker.blazingjokergame.link.LinkPilot
import com.blazingjoker.blazingjokergame.link.config.LinkConfig
import com.blazingjoker.blazingjokergame.link.net.AgentForge
import com.blazingjoker.blazingjokergame.link.push.PushBus

/**
 * The WebView shell — the gray surface.
 *
 * Layout invariants (see the comments in [onCreate]):
 *   • The window is edge-to-edge with cutout `SHORT_EDGES`; the OUTER
 *     [FrameLayout] is painted solid black so the status/nav strips look
 *     like real bezel in either orientation.
 *   • A dedicated INSET container carries the safe-area padding and
 *     hosts the WebView + spinner. Orientation change re-applies insets.
 *   • A BLACK COVER sits above the WebView until the very first main-
 *     frame page actually commits. Chromium's default error page
 *     (`net::ERR_TOO_MANY_REDIRECTS`, `INTERNET_DISCONNECTED`, the robot
 *     glyph, etc.) is thus never visible — the user sees either black
 *     that turns into real content, or black that turns into NoLink.
 */
class WebCanvasActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var cover: FrameLayout
    private lateinit var root: FrameLayout
    private lateinit var safeHost: FrameLayout

    private var lastMainFrameUrl: String? = null
    private var offlineShown = false
    private var pageReady = false

    // True once a page has actually stayed on screen. Until then every
    // main-frame `onPageStarted` is treated as another hop of the entry
    // redirect chain — the cover stays up and the user only ever sees the
    // final destination. After the first settled page, ordinary
    // navigations resolve behind the page the user is already reading.
    private var chainSettled = false

    // Deepest main-frame URL Chromium was seen driving to, settled or not.
    // Affiliate redirect chains routinely blow past Chromium's 20-hop
    // safety limit; resuming from here keeps the cookies picked up along
    // the way, whereas restarting from the entry URL just walks the same
    // loop and burns the budget on identical hops.
    private var farthestHop: String? = null

    // How many times we have resumed the redirect chain from
    // [farthestHop] on this Activity instance. Each resume buys another
    // 20-hop budget from Chromium; [REDIRECT_LOOP_BUDGET] is how many of
    // those we allow before falling back to the entry URL.
    private var redirectRetries = 0

    // One fallback to the configured entry URL per settled page.
    private var entryPointRetried = false

    // A recovery reload is in flight — do NOT surrender from connectivity
    // flips until it commits (or a fresh error fires). Cleared by the very
    // next successful `onPageFinished`.
    private var retryPending = false

    // Reset on every `onPageStarted`, set inside `onReceivedError`. Guards
    // against the case where Chromium still calls `onPageFinished` on a
    // navigation that produced a hard error (ERR_TOO_MANY_REDIRECTS and
    // friends): without this flag, `onPageFinished` would drop the black
    // cover — the user briefly sees the native Chromium error page.
    private var hasErroredThisLoad = false
    private lateinit var kbd: KbdSlide
    private var carrierWatcher: ConnectivityManager.NetworkCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private val dropDebounce = Runnable {
        // Never drop to NoLink from a spurious network flip. Real drops
        // reproduce here: (a) `retryPending` clears once the recovery
        // reload commits — do not surrender during recovery; (b) the
        // system-level `hasCarrier()` re-check is the source of truth
        // after the debounce, because `onLost` fires for individual
        // network handles too (Wi-Fi → cellular hand-off), even when the
        // device still has connectivity via the other transport.
        if (retryPending) {
            android.util.Log.d(
                "WebCanvasActivity",
                "dropDebounce: retryPending — skip offline"
            )
            return@Runnable
        }
        val stillOnline = runCatching {
            LinkPilot.of(this@WebCanvasActivity).auditor.hasCarrier()
        }.getOrDefault(false)
        if (stillOnline) {
            android.util.Log.d(
                "WebCanvasActivity",
                "dropDebounce: still have carrier — skip offline"
            )
            return@Runnable
        }
        forwardToOffline()
    }
    private val coverTimeout = Runnable {
        // Safety net for a page that never reports back — never leave the
        // user under an opaque overlay for longer than [COVER_MAX_MS].
        if (cover.visibility == View.VISIBLE) hideCover()
    }

    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooser = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = pendingFileCallback
        pendingFileCallback = null
        if (cb == null) return@registerForActivityResult
        if (result.resultCode != Activity.RESULT_OK) {
            cb.onReceiveValue(emptyArray())
            return@registerForActivityResult
        }
        val data = result.data
        val uris = mutableListOf<Uri>()
        val clip = data?.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        } else data?.data?.let { uris.add(it) }
        cb.onReceiveValue(uris.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PushBus.shellAlive = true

        // Edge-to-edge so the WebView can extend under the status/nav
        // strips; the safe-area padding below re-establishes a bezel
        // that never covers content.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        Ui.immersive(this)

        root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.BLACK)
        }

        safeHost = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.BLACK)
        }
        root.addView(safeHost)

        web = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            // White — matches the light theme of most partner surfaces
            // so the first paint doesn't flash black before the DOM
            // resolves.
            setBackgroundColor(Color.WHITE)
            // Focus flags required for the IME to open when a text field
            // is tapped inside the WebView — without both, Android files
            // the tap but never asks for input.
            isFocusable = true
            isFocusableInTouchMode = true
            configureWeb(this)
        }
        safeHost.addView(web)

        // Keyboard slider — moves the WebView via translationY instead of
        // letting Chromium shrink the viewport (which double-scrolls the
        // focused field and reads as "input running away from caret").
        // Same technique as magma-coins' KbdSlide / Coin_Pulse's ImeSlider.
        //
        // `onImeChange` fires on the IME animation's prepare AND end. Both
        // must re-assert immersive: Android reveals the status bar the
        // moment the IME opens, and if we don't hide it back the decorView
        // height changes DURING the ride and KbdSlide's `slide()` picks up
        // a different `span`, pushing the WebView too far up. The reassert
        // is idempotent (WindowInsetsController's `hide()` is a no-op when
        // the bar is already hidden), so calling it repeatedly is cheap.
        kbd = KbdSlide(window.decorView, onImeChange = { Ui.immersive(this) }).also {
            it.attach()
            it.follow(web)
        }

        // The cover is deliberately the LAST child of root (above the
        // safe host) so it can hide the WebView's own paint until we
        // know a real page has committed. The spinner lives INSIDE the
        // cover so the user sees a black-and-loading screen rather than a
        // silent black rectangle — same shape as `foollegends/StreamPortal`
        // and `magma-coins/StreamActivity`.
        cover = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.BLACK)
            isClickable = true
            addView(
                ProgressBar(this@WebCanvasActivity).apply {
                    isIndeterminate = true
                    indeterminateTintList = ColorStateList.valueOf(COVER_ACCENT)
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
        }
        root.addView(cover)
        // Arm the timeout up front — the first `onPageFinished` for a
        // real page cancels it, otherwise it lifts the cover on its own.
        handler.postDelayed(coverTimeout, COVER_MAX_MS)

        // Applied to safeHost only — root stays fully painted so the
        // strips look like device bezel regardless of orientation.
        //
        // NB: bottom padding is deliberately ZERO. If the WebView stops
        // short of the window bottom by `bars.bottom` (nav bar height),
        // [KbdSlide] over-lifts by exactly that amount — the class reads
        // the IME height from `decorView.bottom` but computes the target
        // as `webView.height - imeH`, and the two are only equal when the
        // WebView reaches all the way to `decorView.bottom`. Same setup
        // in `foollegends/StreamPortal.applyInsets` and
        // `magma-coins/StreamActivity.installCutoutPadding`.
        ViewCompat.setOnApplyWindowInsetsListener(safeHost) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, 0)
            insets
        }

        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val canGoBack = web.canGoBack()
                android.util.Log.d(
                    "WebCanvasActivity",
                    "OnBackPressedCallback: canGoBack=$canGoBack url=$lastMainFrameUrl"
                )
                if (canGoBack) navigateBackInWeb()
                // else: do nothing — swallow the press so we stay on the
                // current WebView page. The callback is always enabled
                // so the dispatcher must not fall through to the
                // Activity's default `finish()`.
            }
        })

        val destination = intent.getStringExtra(EXTRA_URL).orEmpty()
        val boot = destination.ifEmpty { LinkConfig.homeUrl() }
        if (boot.isNotEmpty()) web.loadUrl(boot)

        subscribeCarrierWatch()
    }

    private fun configureWeb(w: WebView) {
        WebView.setWebContentsDebuggingEnabled(false)
        val s: WebSettings = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.userAgentString = AgentForge.line
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.mediaPlaybackRequiresUserGesture = false
        s.allowContentAccess = true
        s.allowFileAccess = false
        s.cacheMode = WebSettings.LOAD_DEFAULT

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(w, true)

        w.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                // Fresh navigation — reset the error latch so the next
                // `onPageFinished` can lift the cover if the load succeeds.
                hasErroredThisLoad = false
                if (!url.isNullOrBlank() && url != BLANK) {
                    farthestHop = url
                }
                // Every hop of the entry chain gets a cover; once a page
                // has settled, ordinary navigations resolve behind the page
                // the user is already reading and DO NOT bring the cover
                // back — that would look like the app blinking to black in
                // the middle of a session.
                if (!chainSettled && !url.isNullOrBlank() && url != BLANK) {
                    showCover()
                }
                // The mid-session recovery path may have hidden the
                // WebView after an errored click; make sure it's back
                // before Chromium tries to paint the retry.
                if (web.visibility != View.VISIBLE) web.visibility = View.VISIBLE
                // Every navigation gives Chromium an excuse to reveal the
                // status/nav bars (WebView takes focus internally without
                // the Activity window ever losing focus, so onWindowFocus-
                // Changed never fires and immersive isn't re-asserted).
                // Re-hide the bars now and again in onPageFinished so the
                // KbdSlide math (which assumes stable decorView height)
                // isn't blindsided by a fresh top-inset appearing.
                Ui.immersive(this@WebCanvasActivity)
                // Any new page has nothing focused — clear the last field
                // position so the slider resets to 0 before the new page's
                // own focus event fires.
                kbd.forgetField()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                Ui.immersive(this@WebCanvasActivity)
                // A finish that follows a main-frame error is Chromium
                // wrapping up the failed navigation — the recovery reload
                // has not committed yet, so do NOT drop the cover or reset
                // the retry budget on top of it.
                if (hasErroredThisLoad || url.isNullOrBlank() || url == BLANK) return
                pageReady = true
                chainSettled = true
                lastMainFrameUrl = url
                farthestHop = url
                // A page that stayed is the end of a chain — the next
                // `onReceivedError` gets a fresh retry budget.
                redirectRetries = 0
                entryPointRetried = false
                retryPending = false
                hideCover()
                view?.let { LensInjector.installAll(it) }
                // Install the field-position probe once per document —
                // idempotent thanks to the `MARK` guard inside the script.
                view?.evaluateJavascript(kbd.probe, null)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val uri = request?.url ?: return false
                if (request.isForMainFrame) {
                    lastMainFrameUrl = uri.toString()
                    farthestHop = uri.toString()
                    // A user tap is proof a real page is on screen — the
                    // next load is a navigation, not another entry hop.
                    if (request.hasGesture()) chainSettled = true
                }
                val scheme = uri.scheme?.lowercase()
                val inApp = scheme == "http" || scheme == "https" || scheme == "about" ||
                    scheme == "data" || scheme == "blob"
                if (inApp) return false
                openExternal(uri)
                return true
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame != true) return

                // Latch the error so the trailing `onPageFinished` for
                // this same navigation does NOT flip pageReady=true and
                // uncover the Chromium error page.
                hasErroredThisLoad = true

                // Belt-and-braces: even if the cover somehow lost its
                // priority (mid-session error after `pageReady` had lifted
                // it), hiding the WebView itself guarantees the native
                // Chromium error chrome cannot leak into the frame.
                web.visibility = View.INVISIBLE
                showCover()

                val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    error?.errorCode ?: 0 else 0
                val desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    error?.description?.toString().orEmpty() else ""

                // Chromium's 20-hop safety limit — expected for the
                // affiliate redirect chain the hosted surface kicks off.
                // Resume from the deepest hop, NOT from lastMainFrameUrl
                // (that walks the same hops again and burns the budget on
                // the identical loop).
                //   -9    = WebViewClient.ERROR_REDIRECT_LOOP
                //   -1007 = Chromium's ERR_TOO_MANY_REDIRECTS on some ROMs
                val loopish = code == ERROR_REDIRECT_LOOP || code == -1007 ||
                    desc.contains("too_many", ignoreCase = true) ||
                    desc.contains("REDIRECT", ignoreCase = true)
                if (loopish) {
                    handleRedirectLoop(view, request.url.toString())
                    return
                }

                // Any other main-frame error: try the last page that
                // actually committed, then surrender to NoLink only if we
                // never had one.
                val restoreTo = if (pageReady) lastMainFrameUrl else null
                if (restoreTo != null) {
                    retryPending = true
                    view?.postDelayed({
                        if (!isFinishing && !isDestroyed) {
                            runCatching {
                                view.stopLoading()
                                view.loadUrl(restoreTo)
                            }
                        }
                    }, RELOAD_GRACE_MS)
                    return
                }

                runCatching {
                    view?.stopLoading()
                    view?.loadUrl(BLANK)
                }
                forwardToOffline()
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: android.net.http.SslError?,
            ) {
                handler?.cancel()
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                // Backstop for a page that reports progress but never a
                // finished load. `about:blank` is only ever loaded on the
                // way out to the offline screen, so its 100% says nothing
                // about the page the user is actually waiting for.
                val url = view?.url
                if (newProgress < 100 || url == null || url == BLANK) return
                if (hasErroredThisLoad) return
                pageReady = true
                chainSettled = true
                lastMainFrameUrl = url
                hideCover()
            }

            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                pendingFileCallback?.onReceiveValue(emptyArray())
                pendingFileCallback = filePathCallback
                val intent = fileChooserParams?.createIntent()
                    ?: Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
                return try {
                    fileChooser.launch(Intent.createChooser(intent, null))
                    true
                } catch (_: ActivityNotFoundException) {
                    pendingFileCallback = null
                    Toast.makeText(this@WebCanvasActivity, "No file picker", Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }
    }

    /**
     * Puts the black-and-spinner cover back over the WebView. Idempotent —
     * repeated calls just keep the existing surface up and re-arm the
     * safety timeout so a page that never reports back cannot hold the
     * screen for good.
     */
    private fun showCover() {
        cover.visibility = View.VISIBLE
        cover.bringToFront()
        handler.removeCallbacks(coverTimeout)
        handler.postDelayed(coverTimeout, COVER_MAX_MS)
    }

    private fun hideCover() {
        handler.removeCallbacks(coverTimeout)
        cover.visibility = View.GONE
    }

    /**
     * `ERR_TOO_MANY_REDIRECTS`. Chromium gives up after 20 hops; affiliate
     * chains routinely need more. Reloading from [farthestHop] resumes
     * the chain instead of walking it from scratch, so cookies picked up
     * along the way stay in force. The reload is posted rather than
     * called directly — the engine is still unwinding the failed
     * navigation and swallows or defers a re-entrant `loadUrl`.
     */
    private fun handleRedirectLoop(view: WebView?, failedUrl: String) {
        val engine = view ?: return
        if (redirectRetries < REDIRECT_LOOP_BUDGET) {
            redirectRetries++
            retryPending = true
            val resumeAt = farthestHop ?: failedUrl
            engine.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    runCatching { engine.loadUrl(resumeAt) }
                }
            }, RELOAD_GRACE_MS)
            return
        }
        val entryUrl = intent.getStringExtra(EXTRA_URL)
        if (!entryPointRetried && !entryUrl.isNullOrBlank() && entryUrl != farthestHop) {
            entryPointRetried = true
            retryPending = true
            engine.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    runCatching { engine.loadUrl(entryUrl) }
                }
            }, RELOAD_GRACE_MS)
            return
        }
        // Budget spent AND the entry URL was tried. Only surrender to the
        // offline screen if we can PROVE there is no carrier — otherwise
        // the loop is a partner-side glitch and dropping into NoLink here
        // would look to the user like "no wifi appeared while wifi is on"
        // (reproduced when back-navigating through a chain that used to
        // resolve but now re-triggers the redirect budget). Reload the
        // last page that actually committed instead; the user stays where
        // they were.
        val stillOnline = runCatching {
            LinkPilot.of(this@WebCanvasActivity).auditor.hasCarrier()
        }.getOrDefault(false)
        if (stillOnline) {
            android.util.Log.d(
                "WebCanvasActivity",
                "handleRedirectLoop: budget exhausted but carrier still up — " +
                    "reloading last committed page instead of NoLink"
            )
            val restoreTo = lastMainFrameUrl
            if (!restoreTo.isNullOrBlank() && restoreTo != BLANK) {
                retryPending = true
                redirectRetries = 0
                entryPointRetried = false
                engine.postDelayed({
                    if (!isFinishing && !isDestroyed) {
                        runCatching {
                            engine.stopLoading()
                            engine.loadUrl(restoreTo)
                        }
                    }
                }, RELOAD_GRACE_MS)
                return
            }
            // No last-committed page yet — leave the cover up rather than
            // surrender; the cover timeout will lift it after COVER_MAX_MS.
            retryPending = false
            return
        }
        retryPending = false
        runCatching {
            engine.stopLoading()
            engine.loadUrl(BLANK)
        }
        forwardToOffline()
    }

    private fun openExternal(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Silent — user probably has no app installed for tel:/mailto:
        }
    }

    private fun subscribeCarrierWatch() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val req = NetworkRequest.Builder().build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                handler.removeCallbacks(dropDebounce)
                handler.postDelayed(dropDebounce, LinkConfig.LINK_DROP_DEBOUNCE_MS)
            }

            override fun onAvailable(network: Network) {
                handler.removeCallbacks(dropDebounce)
            }
        }
        carrierWatcher = cb
        try {
            cm.registerNetworkCallback(req, cb)
        } catch (_: Throwable) {
            carrierWatcher = null
        }
    }

    private fun guardOffline() {
        // Fire-and-forget probe — if it turns out we still have a route
        // (e.g. a single asset failed), do NOT show the offline screen.
        Thread {
            val ok = kotlinx.coroutines.runBlocking {
                LinkPilot.of(this@WebCanvasActivity).auditor.canRouteOut()
            }
            if (!ok) handler.post { forwardToOffline() }
        }.start()
    }

    private fun forwardToOffline() {
        if (offlineShown) return
        // Hard invariant: NoLink is a "no wifi" screen. Never show it
        // while the device still has a carrier — this used to flash a
        // false NoLink between a mid-session error and the actual
        // recovery reload, and users read it as "the app is broken".
        // Callers that need to nudge the WebView back to a working
        // page have their own paths (onReceivedError → restoreTo,
        // handleRedirectLoop → reload lastMainFrameUrl). This method
        // is exclusively the "we truly have no network" trapdoor.
        val stillOnline = runCatching {
            LinkPilot.of(this).auditor.hasCarrier()
        }.getOrDefault(false)
        if (stillOnline) {
            android.util.Log.d(
                "WebCanvasActivity",
                "forwardToOffline: skipped, still have carrier"
            )
            return
        }
        offlineShown = true
        val currentUrl = lastMainFrameUrl ?: intent.getStringExtra(EXTRA_URL).orEmpty()
        val i = Intent(this, NoLinkActivity::class.java).apply {
            putExtra(NoLinkActivity.EXTRA_RETRY_URL, currentUrl)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(i)
        finish()
    }

    override fun onResume() {
        super.onResume()
        Ui.immersive(this)
    }

    /**
     * Belt-and-braces safety net.
     *
     * The primary back handler is the [OnBackPressedCallback] registered
     * in [onCreate], but on some OEM builds (ColorOS 15 on realme, some
     * MIUI versions) the platform's OnBackInvokedDispatcher registers
     * the Activity's DEFAULT callback (`finish()`) with priority -1
     * BEFORE AndroidX gets a chance to swap in our OnBackPressedCallback
     * — and the OS ends up firing the default handler, closing the
     * shell. Explicitly overriding `onBackPressed` catches every path
     * the AndroidX bridge might miss and keeps the app on the WebView.
     * We never call `super.onBackPressed()` here, because doing so is
     * what closes the Activity.
     */
    @Deprecated(
        "AndroidX prefers OnBackPressedCallback, but we keep this override " +
            "as a hard fallback for the OEM back-dispatcher races described above."
    )
    override fun onBackPressed() {
        val canGoBack = web.canGoBack()
        android.util.Log.d(
            "WebCanvasActivity",
            "onBackPressed(): canGoBack=$canGoBack url=$lastMainFrameUrl"
        )
        if (canGoBack) {
            navigateBackInWeb()
        }
        // else: swallow. Never fall through to super — that finishes
        // the Activity and closes the app.
    }

    /**
     * Second safety net for physical HW back keys and OEM key routing
     * that skips the OnBackInvokedDispatcher altogether. Returning
     * `true` here consumes the event without ever invoking the
     * Activity's default back behaviour.
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            val canGoBack = web.canGoBack()
            android.util.Log.d(
                "WebCanvasActivity",
                "onKeyDown(BACK): canGoBack=$canGoBack url=$lastMainFrameUrl"
            )
            if (canGoBack) navigateBackInWeb()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Wraps `web.goBack()` with a redirect-budget reset. Without this the
     * chain the user is walking back through can immediately trip
     * [handleRedirectLoop] with a stale, already-decremented retry count
     * — the user then sees the NoLink screen appear after "just pressing
     * back". Every back navigation deserves a fresh 2-hop budget.
     */
    private fun navigateBackInWeb() {
        redirectRetries = 0
        entryPointRetried = false
        retryPending = false
        hasErroredThisLoad = false
        web.goBack()
    }

    override fun onStart() {
        super.onStart()
        // Subscribe to warm push URLs so a tap while the shell is on
        // screen loads the URL into the live WebView rather than
        // triggering a full re-dispatch through LoadingActivity.
        PushBus.onWarmUrl = { url ->
            runOnUiThread {
                if (!isFinishing && !isDestroyed) loadPushUrl(url)
            }
        }
        // Warm re-invite. Cold launches route through LoadingActivity
        // which already asks `shouldInvitePermission()` before
        // dispatching, but a warm relaunch (task resumed from recents,
        // launcher tap on a still-alive process) bypasses
        // LoadingActivity entirely — without this check the "Skip →
        // 2 d 20 h later" reappearance never fires for users who never
        // kill the app between test runs. shouldInvitePermission()
        // already returns false when granted/hard-blocked/still-snoozed,
        // so this cannot loop.
        maybeReInviteNotifications()
    }

    private var reInviteInFlight = false

    private fun maybeReInviteNotifications() {
        if (reInviteInFlight) return
        val pilot = LinkPilot.of(this)
        if (!pilot.stowage.shouldInvitePermission(this)) return
        android.util.Log.d(
            "WebCanvasActivity",
            "onStart: snooze elapsed → showing AlertOptIn on top"
        )
        // Latch until the AlertOptIn has had time to reach the
        // foreground; without this, a double onStart (config change,
        // OEM window-focus race) would stack two AlertOptIn copies.
        // Cleared on the very next onStart after user interaction
        // (Skip/Accept refreshes the snooze, so the check below returns
        // false and no relaunch happens anyway — the flag is only a
        // guard against the double-fire race, not the loop guard).
        reInviteInFlight = true
        handler.postDelayed({ reInviteInFlight = false }, 2_000L)
        // Empty destination on purpose — AlertOptInActivity.forward()
        // treats an empty EXTRA_DESTINATION_URL as "just close and go
        // back to whoever launched me", so the WebView underneath keeps
        // its session.
        val i = Intent(this, AlertOptInActivity::class.java)
        startActivity(i)
    }

    override fun onStop() {
        // A backgrounded shell must not eat URLs meant for the next
        // foregrounded one. Cold-tap URLs will run through LoadingActivity
        // instead when no live subscriber is present.
        PushBus.onWarmUrl = null
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val url = intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() } ?: return
        loadPushUrl(url)
    }

    /**
     * Load a URL into the existing WebView (in-session push tap / warm
     * hand-off). The chain has already settled by this point — the user
     * is reading a page — so we drop the cover immediately after start
     * rather than raising it, keeping the transition feel like a normal
     * in-page navigation.
     */
    private fun loadPushUrl(url: String) {
        if (!::web.isInitialized) return
        chainSettled = true
        offlineShown = false
        hasErroredThisLoad = false
        retryPending = false
        // A brief cover while the load starts stops the WebView from
        // painting the previous page's `about:blank` flash between
        // stopLoading() and the new frame commit.
        showCover()
        runCatching {
            web.stopLoading()
            web.loadUrl(url)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // A rotation recomputes bar heights AND the keyboard rest height —
        // let the slider re-measure both, otherwise the first field tap
        // after rotating lifts by the previous orientation's offset.
        if (::kbd.isInitialized) kbd.reorient()
    }

    override fun onDestroy() {
        PushBus.onWarmUrl = null
        PushBus.shellAlive = false
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        carrierWatcher?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        handler.removeCallbacks(dropDebounce)
        handler.removeCallbacks(coverTimeout)
        pendingFileCallback?.onReceiveValue(emptyArray())
        pendingFileCallback = null
        web.stopLoading()
        web.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "bj_web_url"

        private const val BLANK = "about:blank"

        // WebViewClient error-code constants — mirror them here so we can
        // rebuild without the transient ERROR_* imports that older API
        // levels omit.
        private const val ERROR_REDIRECT_LOOP = -9

        // How many times a chain may be resumed from [farthestHop] before
        // we fall back to the entry URL. Each resume buys another 20-hop
        // budget from Chromium, so two resumes cover the deepest affiliate
        // chains we have seen without ever letting the loop spin forever.
        private const val REDIRECT_LOOP_BUDGET = 2

        // Grace before the queued reload actually fires. Long enough for
        // Chromium to finish unwinding the failed navigation, short
        // enough to feel instant.
        private const val RELOAD_GRACE_MS = 60L

        // No page may hold the cover for longer than this, finished or
        // not — otherwise a hosted surface that reports progress and then
        // silently stalls would leave the user under a black overlay for
        // good.
        private const val COVER_MAX_MS = 20_000L

        // Spinner tint on the cover. Warm gold matches the loading art
        // that immediately precedes this Activity, so the two screens
        // look like one continuous frame.
        private val COVER_ACCENT = Color.parseColor("#FFE0A344")

        fun start(context: Context, url: String) {
            val i = Intent(context, WebCanvasActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(i)
        }
    }
}
