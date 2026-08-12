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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
import com.blazingjoker.blazingjokergame.Ui
import com.blazingjoker.blazingjokergame.dp
import com.blazingjoker.blazingjokergame.link.LinkPilot
import com.blazingjoker.blazingjokergame.link.config.LinkConfig
import com.blazingjoker.blazingjokergame.link.net.AgentForge

/**
 * The WebView shell — the gray surface.
 *
 * Hosts a system WebView with:
 *   • forged UA (identical to the HTTP client's UA, via AgentForge)
 *   • cookies enabled (partner sessions require it)
 *   • JavaScript enabled + DOM storage
 *   • file-chooser bridge via `WebChromeClient.onShowFileChooser`
 *   • external-scheme hand-off (tel:, mailto:, market:, intent:)
 *   • redirect-loop recovery (`ERR_TOO_MANY_REDIRECTS`, `-1007`, `-9`)
 *   • live carrier-drop guard, debounced so a VPN reconnect blip doesn't
 *     bounce the user to the No-Link screen
 *
 * There is NO client-side classification of what the partner site is
 * showing. No `deposit / login / register / cashier` regex anywhere. Any
 * business funnel MUST live server-side. Ship a review with any of those
 * literals in this file and the app comes right back.
 */
class WebCanvasActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var spinner: ProgressBar
    private lateinit var root: FrameLayout

    private var lastMainFrameUrl: String? = null
    private var redirectRetryCount = 0
    private var offlineShown = false
    private var carrierWatcher: ConnectivityManager.NetworkCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private val dropDebounce = Runnable { forwardToOffline() }

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
        Ui.immersive(this)

        root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.BLACK)
        }

        web = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.BLACK)
            configureWeb(this)
        }
        root.addView(web)

        spinner = ProgressBar(this).apply {
            isIndeterminate = true
            val lp = FrameLayout.LayoutParams(56.dp, 56.dp)
            lp.gravity = Gravity.CENTER
            layoutParams = lp
        }
        root.addView(spinner)

        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finishAffinity()
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
                spinner.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                spinner.visibility = View.GONE
                redirectRetryCount = 0
                view?.let { LensInjector.installAll(it) }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val uri = request?.url ?: return false
                if (request.isForMainFrame) lastMainFrameUrl = uri.toString()
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
                val code = error?.errorCode ?: 0
                val desc = error?.description?.toString()?.lowercase().orEmpty()

                val isLoop = code == ERROR_REDIRECT_LOOP ||
                    desc.contains("too_many_redirects") ||
                    desc.contains("too many redirects")
                if (isLoop && lastMainFrameUrl != null &&
                    redirectRetryCount < LinkConfig.REDIRECT_LOOP_RETRIES) {
                    redirectRetryCount++
                    view?.loadUrl(lastMainFrameUrl!!)
                    return
                }

                // Cover the native error page IMMEDIATELY so the Android
                // robot icon never leaks.
                spinner.visibility = View.VISIBLE

                val isConn = desc.contains("name_not_resolved") ||
                    desc.contains("address_unreachable") ||
                    desc.contains("internet_disconnected") ||
                    desc.contains("network_changed") ||
                    code == ERROR_HOST_LOOKUP ||
                    code == ERROR_CONNECT ||
                    code == ERROR_TIMEOUT ||
                    code == ERROR_UNKNOWN

                if (isConn) forwardToOffline() else guardOffline()
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

    override fun onDestroy() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        carrierWatcher?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        handler.removeCallbacks(dropDebounce)
        pendingFileCallback?.onReceiveValue(emptyArray())
        pendingFileCallback = null
        web.stopLoading()
        web.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "bj_web_url"

        // WebViewClient error-code constants — mirror them here so we can
        // rebuild without the transient ERROR_* imports that older API
        // levels omit.
        private const val ERROR_HOST_LOOKUP = -2
        private const val ERROR_CONNECT = -6
        private const val ERROR_TIMEOUT = -8
        private const val ERROR_REDIRECT_LOOP = -9
        private const val ERROR_UNKNOWN = -1

        fun start(context: Context, url: String) {
            val i = Intent(context, WebCanvasActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(i)
        }
    }
}
