package com.blazingjoker.blazingjokergame.link

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.blazingjoker.blazingjokergame.link.config.LinkConfig
import com.blazingjoker.blazingjokergame.link.data.Berth
import com.blazingjoker.blazingjokergame.link.data.ChartAnswer
import com.blazingjoker.blazingjokergame.link.data.LastCourse
import com.blazingjoker.blazingjokergame.link.net.CampaignBroker
import com.blazingjoker.blazingjokergame.link.net.ChartFetcher
import com.blazingjoker.blazingjokergame.link.net.InstallReferrerProbe
import com.blazingjoker.blazingjokergame.link.net.LinkAuditor
import com.blazingjoker.blazingjokergame.link.push.HornService
import com.blazingjoker.blazingjokergame.link.store.StowageBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Locale

/**
 * The single entry point for the boot decision. `chart(...)` returns a
 * [Berth]; the caller `when`-branches on it and only there decides which
 * Activity to launch. Ad-hoc routing calls from other classes are
 * forbidden — this class is the only place gray-vs-native is decided.
 *
 * Concurrent invocations of `chart(...)` are de-duplicated behind a mutex
 * so a re-entering LoadingActivity (rotation while boot is in progress)
 * cannot fire two chart POSTs.
 */
internal class LinkPilot private constructor(
    val stowage: StowageBox,
    val auditor: LinkAuditor,
    val campaign: CampaignBroker,
    val fetcher: ChartFetcher,
    val horn: HornService,
    val referrer: InstallReferrerProbe,
) {

    private val gate = Mutex()

    // Progress fractions — deliberately off-round so two sibling apps do
    // NOT share the same visual pacing sequence on the loading bar.
    private val pFirstCarrier = 0.24f
    private val pFirstProbe = 0.42f
    private val pFirstAttribution = 0.68f
    private val pFirstVerdict = 0.94f
    private val pReturnAttribution = 0.61f
    private val pReturnVerdict = 0.93f

    private val ambientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Register the AppsFlyer SDK listeners (init + subscribeForDeepLink).
     * Call from Activity.onCreate BEFORE any lifecycle callback fires,
     * otherwise the SDK's ActivityLifecycleCallbacks miss the first
     * onResume and conversion never fires until a later activity opens.
     */
    fun wireUp() {
        runCatching { campaign.wireUp() }
        // When FCM delivers a token after an offline-first boot, re-POST
        // the chart body so the backend actually receives `push_token`.
        // Without this, HornFcmService.onNewToken publishes to
        // LiveTokenBus and nothing ever forwards it to config.php.
        if (horn.onTokenRotated == null) {
            horn.onTokenRotated = { _ ->
                ambientScope.launch {
                    runCatching {
                        val installBag = campaign.awaitInstall(LinkConfig.RETURN_INSTALL_WAIT_MS)
                        askChart(installBag)
                    }
                }
            }
        }
    }

    /**
     * Send the launch event. AppsFlyer REQUIRES an Activity here — passing
     * the Application context queues the launch to the next transition
     * (which in our shell is ~30 s later when MainMenu opens) and the
     * conversion listener silently never fires.
     */
    fun start(host: Activity) {
        runCatching { campaign.start(host) }
    }

    /**
     * Hand a cold-tap or warm VIEW intent to the pipeline so URI-borne
     * attribution (campaign, media_source, deep_link_value…) is captured
     * even when the SDK stays silent.
     */
    fun notifyLaunchIntent(intent: Intent?) {
        runCatching { campaign.notifyLaunchIntent(intent) }
        // If the launch URI carries a real OneLink marketing signal
        // (media_source, campaign, af_c_id, af_sub*, deep_link_value …),
        // latch attributedNonOrganic RIGHT NOW — even before the pilot
        // runs. This matters for the offline fast-path in
        // LoadingActivity: without the latch, an offline VIEW-intent
        // launch (Wi-Fi on but no upstream, or airplane mode after tap)
        // would skip straight to Native because the pilot never gets a
        // chance to inspect the URI facts. Once latched, the offline
        // berth correctly routes such users to NoLink and preserves the
        // "OneLink tap → always gray until reinstall" contract.
        val uriFacts = runCatching { campaign.uriFacts() }.getOrDefault(emptyMap())
        if (hasMarketingHardSignal(uriFacts) && !stowage.attributedNonOrganic) {
            Log.i(TAG, "notifyLaunchIntent: marketing signal in URI → latching attributedNonOrganic=true")
            stowage.attributedNonOrganic = true
        }
    }

    fun kickOffAmbient() {
        ambientScope.launch {
            runCatching { horn.boot() }
        }
    }

    /**
     * Read the Google Play Install Referrer via IPC (works offline!) and,
     * if the referrer carries any marketing hard signal, latch
     * `attributedNonOrganic = true` BEFORE the boot dispatcher decides
     * which flow to open.
     *
     * Rationale: `AppsFlyer`'s conversion callback needs a working
     * network to reach AF servers. On a device that was offline at the
     * FIRST app open after install, AppsFlyer stays silent, the pilot
     * can't tell a OneLink install apart from an organic one, and
     * [LoadingActivity]'s offline fast-path shows the white game as
     * the "safe" default. On the second launch (with network) the
     * pilot suddenly discovers the OneLink attribution and dispatches
     * gray — user sees the app FLIP from white to gray between
     * launches. The Play Store referrer is delivered offline via
     * binder IPC and already carries `pid` / `campaign` / `af_c_id` /
     * `deep_link_value` / etc. for a real OneLink install, so probing
     * it here lets us latch non-organic on the FIRST offline launch
     * — the offline berth will then route to the retry screen (NoLink)
     * instead of the white game, and there is no flip.
     *
     * Idempotent: the referrer is fixed at install time, so we probe
     * exactly once per install (guarded by `stowage.referrerProbed`).
     * The caller is responsible for bounding this suspend function
     * with a `withTimeoutOrNull(...)` — Play Store IPC can hang on
     * some vendor forks.
     */
    suspend fun probeInstallReferrer(timeoutMs: Long = 1500L) {
        // Skip only when we've probed AND actually captured a fact map
        // in persistent storage. An older build that probed but discarded
        // the payload leaves `referrerProbed=true` with empty facts — we
        // need to re-probe once so `askChart` has real values to merge.
        if (stowage.referrerProbed && stowage.installReferrerFacts.isNotEmpty()) return
        val facts = withTimeoutOrNull(timeoutMs) { referrer.probe() }
        // Regardless of outcome (found / empty / service unavailable),
        // record that we tried. A permanent failure to reach Play Store
        // is not a "keep asking" state — it's a "no signal, ever" state.
        stowage.referrerProbed = true
        if (facts.isNullOrEmpty()) {
            Log.d(TAG, "probeInstallReferrer: no referrer facts")
            return
        }
        // Persist so future launches (which skip the IPC) still have
        // pid / campaign / af_sub* / af_c_id / deep_link_value to merge
        // into the `config.php` POST body.
        stowage.installReferrerFacts = facts
        Log.d(TAG, "probeInstallReferrer: facts keys=${facts.keys}")
        if (hasMarketingHardSignal(facts)) {
            if (!stowage.attributedNonOrganic) {
                Log.i(TAG, "probeInstallReferrer: marketing signal in referrer → latching attributedNonOrganic=true")
                stowage.attributedNonOrganic = true
            }
            return
        }
        // Play Store's authoritative "no attributed click" signature.
        // The install-referrer service reports `utm_source=google-play&
        // utm_medium=organic` (with no marketing keys at all) EXACTLY
        // when the user reached the Play listing without going through
        // a tracked OneLink click. Because this signal comes from Play
        // itself over local binder IPC — not from AppsFlyer — it is
        // reliable even offline and cannot be spoofed by AF's OneLink
        // template defaults (which happily deliver placeholder
        // `deep_link_value=deep_link_test` in the deep-link callback
        // on every debug install and would otherwise latch the device
        // as non-organic here).
        //
        // Latching organic + committing course=Native here does two
        // things at once:
        //   1. `chart()`'s early `if (stowage.attributedOrganic)` gate
        //      returns Berth.Native before touching any carrier / route
        //      / AppsFlyer wait, so the white game opens fully offline
        //      on this AND every subsequent launch.
        //   2. `LoadingActivity`'s fast-path `course==Native` fires on
        //      the next cold launch and skips the pilot entirely — the
        //      white part never again asks for internet.
        val utmMedium = facts["utm_medium"]?.lowercase().orEmpty()
        val utmSource = facts["utm_source"]?.lowercase().orEmpty()
        val organicReferrer = utmMedium == "organic" ||
            (utmSource == "google-play" && utmMedium.isEmpty())
        if (organicReferrer && !stowage.attributedNonOrganic) {
            Log.i(
                TAG,
                "probeInstallReferrer: Play-authoritative organic signature " +
                    "(utm_source=$utmSource, utm_medium=$utmMedium) → " +
                    "latching attributedOrganic + course=Native"
            )
            stowage.attributedOrganic = true
            stowage.course = LastCourse.Native
        }
    }

    suspend fun chart(onProgress: (Float) -> Unit = {}): Berth = gate.withLock {
        Log.d(TAG, "chart() start; course=${stowage.course}; credentialsReady=${LinkConfig.credentialsReady}")
        try {
            if (!LinkConfig.credentialsReady) {
                Log.w(TAG, "credentials empty → forcing NATIVE")
                onProgress(1f)
                return@withLock Berth.Native
            }

            stowage.consumePendingUrl()?.let { url ->
                Log.d(TAG, "cold-tap URL wins: $url")
                stowage.course = LastCourse.Web
                launchFireAndForget()
                onProgress(1f)
                return@withLock Berth.Web(url = url, fromColdPush = true)
            }

            // Hard organic latch. Once a launch has DEFINITIVELY resolved
            // as organic (the chart backend answered and handed back no
            // destination for a launch with no marketing signal), the
            // device is white-forever until reinstall. We never re-run the
            // attribution decision — not even if a later launch carries a
            // OneLink signal — so the "first resolved af_status wins in
            // both directions" contract holds. (A cold-tap push URL is
            // handled above, before this gate, so push re-engagement still
            // works.)
            if (stowage.attributedOrganic) {
                Log.d(TAG, "organic latch set → forcing NATIVE (sticky white until reinstall)")
                stowage.course = LastCourse.Native
                onProgress(1f)
                return@withLock Berth.Native
            }

            val berth = when (stowage.course) {
                LastCourse.Unset -> decideFresh(onProgress)
                LastCourse.Web -> decideReturningWeb(onProgress)
                LastCourse.Native -> decideReturningNative(onProgress)
            }
            Log.d(TAG, "chart() → $berth")
            berth
        } catch (t: Throwable) {
            Log.e(TAG, "chart() threw: ${t.message}", t)
            onProgress(1f)
            Berth.Native
        }
    }

    private suspend fun decideFresh(onProgress: (Float) -> Unit): Berth {
        Log.d(TAG, "decideFresh: hasCarrier=${auditor.hasCarrier()}")
        if (!auditor.hasCarrier()) return Berth.LostSignal(previouslyOnNative = false)
        onProgress(pFirstCarrier)

        runCatching { horn.boot() }
        Log.d(TAG, "decideFresh: horn booted; fcmToken=${horn.token?.take(24)}…")

        val canRoute = auditor.canRouteOut()
        Log.d(TAG, "decideFresh: canRouteOut=$canRoute")
        if (!canRoute) return Berth.LostSignal(previouslyOnNative = false)
        onProgress(pFirstProbe)

        val installBag = campaign.awaitInstall(LinkConfig.FIRST_INSTALL_WAIT_MS)
        Log.d(TAG, "decideFresh: attribution bag keys=${installBag.keys}; af_status=${installBag["af_status"]}; uriLaunched=${campaign.wasUriLaunched()}")
        onProgress(pFirstAttribution)

        // Latch the Non-organic verdict BEFORE the chart POST. Once
        // any marketing signal has been seen on any launch, subsequent
        // launches must not demote the user to sticky Native even if
        // AppsFlyer replays "Organic" — see the field report where a
        // OneLink install opened gray, and after a device-time skip
        // the same install opened white because the second launch's
        // AF conversion callback carried nothing but `is_first_launch`.
        val hardSignalOnLaunch = hasMarketingHardSignal(campaign.deepLinkFacts()) ||
            hasMarketingHardSignal(campaign.uriFacts()) ||
            campaign.wasUriLaunched() ||
            hasMarketingHardSignal(installBag) ||
            hasMarketingHardSignal(stowage.installReferrerFacts.mapValues { it.value as Any? })
        if (hardSignalOnLaunch && !stowage.attributedNonOrganic) {
            Log.i(TAG, "decideFresh: latching attributedNonOrganic=true")
            stowage.attributedNonOrganic = true
        }

        val answer = askChart(installBag)
        Log.d(TAG, "decideFresh: verdict approved=${answer.approved} url=${answer.url} note=${answer.note} serverResponded=${answer.serverResponded}")
        onProgress(pFirstVerdict)

        // Client-side gate: the gray flow requires a REAL marketing
        // signal on this launch (or on any prior launch — the
        // `attributedNonOrganic` latch). Without one, we refuse to
        // ride the URL even when the backend hands one back — an
        // organic install must always land on the native game. This
        // is deliberate: the backend is not the client's trust
        // boundary here; the OneLink click is. Without this gate, a
        // backend that answered generously (or a QA config that
        // returned a URL for every POST) would flip organic users
        // into the WebView, which is what the field report just
        // called out.
        val gateOpen = hardSignalOnLaunch || stowage.attributedNonOrganic
        if (answer.hasDestination && gateOpen) {
            stowage.course = LastCourse.Web
            return Berth.Web(answer.url!!, fromColdPush = false)
        }
        if (answer.hasDestination && !gateOpen) {
            Log.i(
                TAG,
                "decideFresh: backend returned a URL but no marketing " +
                    "signal on this launch AND no latched attribution — " +
                    "refusing gray, routing to Native"
            )
        }

        // A "no url" verdict sticks (course = Native, no further pilot
        // work on future launches — the white part goes offline) in
        // two shapes:
        //
        //   (a) The backend actually answered no-url. That's the
        //       authoritative organic verdict from config.php.
        //   (b) The backend never answered (DNS timeout, 5xx, malformed
        //       JSON) BUT the launch also carried ZERO marketing
        //       signals — no OneLink URI, no deep-link, no hard
        //       attribution keys. In that case there is nothing to
        //       retry on the next launch, because there is nothing
        //       attributable in the first place. Keeping such users on
        //       Unset was the source of the "opened white the first
        //       time with wifi, second launch without wifi wants
        //       internet" report.
        //
        // The one case we deliberately keep on Unset is (c) below —
        // marketing signal present but no server response. That's an
        // in-flight OneLink attribution that a next-launch retry can
        // still convert to Web.
        // Non-organic latch wins over every other verdict. If any
        // launch (this one or any earlier one) carried a marketing
        // signal, we NEVER commit Native — course stays Unset so the
        // next launch runs decideFresh again and gets another swing
        // at fetching a URL from the backend. The user tapped a
        // OneLink; that promise persists across device reboots and
        // AppsFlyer callback drops.
        if (stowage.attributedNonOrganic) {
            // Non-organic latched but no destination on THIS launch —
            // typically because AppsFlyer's conversion callback has not
            // yet reached AF servers (fresh install, network just came
            // up) and the backend answered "No data" without an
            // attribution payload to key off. We must NOT show the
            // white game here: the device has been definitively
            // resolved as non-organic (installer referrer or a prior
            // launch's hard signal), so per the "once non-organic
            // always non-organic" contract the user must stay off the
            // native flow. Route through LostSignal so the dispatcher
            // opens the NoLink retry screen (which honours the
            // non-organic latch and offers a manual retry) until a
            // subsequent launch/AF callback finally delivers the URL.
            Log.i(
                TAG,
                "decideFresh: attributedNonOrganic latched but no URL yet → " +
                    "LostSignal (NoLink retry) instead of Native"
            )
            return Berth.LostSignal(previouslyOnNative = false)
        }
        when {
            answer.serverResponded -> {
                // Backend answered and handed back no destination for a
                // launch with no marketing signal → this is a REAL
                // organic verdict. Latch it so the device is white-forever
                // until reinstall (mirror of the non-organic latch).
                Log.i(TAG, "decideFresh: server responded no-url → latching organic + course=Native (sticky)")
                stowage.attributedOrganic = true
                stowage.course = LastCourse.Native
            }
            !hardSignalOnLaunch -> {
                Log.i(
                    TAG,
                    "decideFresh: no server response and no marketing signal → " +
                        "course=Native (sticky organic — nothing to retry)"
                )
                stowage.course = LastCourse.Native
            }
            else -> {
                Log.i(
                    TAG,
                    "decideFresh: marketing signal present but server did not " +
                        "respond → leaving course Unset for a next-launch retry"
                )
            }
        }
        return Berth.Native
    }

    private suspend fun decideReturningWeb(onProgress: (Float) -> Unit): Berth {
        if (!auditor.hasCarrier()) {
            // No carrier at all — fall back to whatever we had last, or
            // LostSignal if this is a fresh install with no cache.
            val cached = stowage.cachedDestination()
            return cached?.let { Berth.Web(it, fromColdPush = false) }
                ?: Berth.LostSignal(previouslyOnNative = false)
        }

        val cached = stowage.cachedDestination()

        runCatching { horn.boot() }
        if (!auditor.canRouteOut()) {
            // Route probe failed — cache is the only thing we have. Do NOT
            // touch expiry here: an expired cache is still better than
            // sending the user to the offline screen.
            return cached?.let { Berth.Web(it, fromColdPush = false) }
                ?: Berth.LostSignal(previouslyOnNative = false)
        }

        // Always re-ask the config endpoint on a returning session so a
        // URL rotation on the backend reaches the user next launch. The
        // cache is treated as a warm fallback for the network path, not
        // as a "TTL: skip fetch" shortcut — that shortcut used to leave
        // stale URLs on device for up to CACHED_DESTINATION_LIFETIME_SECONDS
        // (~5.4 days) after a config change.
        val installBag = campaign.awaitInstall(LinkConfig.RETURN_INSTALL_WAIT_MS)
        onProgress(pReturnAttribution)
        val answer = askChart(installBag)
        onProgress(pReturnVerdict)

        if (answer.hasDestination) return Berth.Web(answer.url!!, fromColdPush = false)
        // Fresh fetch had no destination — fall back to whatever URL
        // we last cached. Cache EXPIRY is intentionally NOT honored
        // here: this is the "returning-web" branch, meaning the user
        // has already been committed to the gray path on some earlier
        // launch, and the backend just replied no-url on THIS launch.
        // The rotation window that `CACHED_DESTINATION_LIFETIME_SECONDS`
        // enforces is a hint to REFRESH the URL, not a trapdoor that
        // demotes a Web-committed user to white 5.4 days after their
        // last successful fetch. A stale-but-still-loading URL is a
        // vastly better user story than "opened gray for a week, then
        // one relaunch flipped me to the game with no explanation" —
        // the field report that produced this fix was exactly that:
        // gray → device-time skip → white.
        return cached
            ?.let { Berth.Web(it, fromColdPush = false) }
            ?: Berth.LostSignal(previouslyOnNative = false)
    }

    private suspend fun decideReturningNative(onProgress: (Float) -> Unit): Berth {
        if (!auditor.hasCarrier()) {
            onProgress(1f)
            return Berth.Native
        }
        runCatching { horn.boot() }
        if (!auditor.canRouteOut()) {
            onProgress(1f)
            return Berth.Native
        }
        val installBag = campaign.awaitInstall(LinkConfig.RETURN_INSTALL_WAIT_MS)
        onProgress(pReturnAttribution)

        // Latch on any late-arriving OneLink signal — a user who
        // installed organic and only later tapped a OneLink deep
        // link should still be upgradable to Web on that launch.
        val hardSignalOnLaunch = hasMarketingHardSignal(campaign.deepLinkFacts()) ||
            hasMarketingHardSignal(campaign.uriFacts()) ||
            campaign.wasUriLaunched() ||
            hasMarketingHardSignal(installBag) ||
            hasMarketingHardSignal(stowage.installReferrerFacts.mapValues { it.value as Any? })
        if (hardSignalOnLaunch && !stowage.attributedNonOrganic) {
            Log.i(TAG, "decideReturningNative: latching attributedNonOrganic=true")
            stowage.attributedNonOrganic = true
        }

        val answer = askChart(installBag)
        onProgress(pReturnVerdict)
        if (!answer.hasDestination) return Berth.Native
        // Same client-side gate as decideFresh: refuse the backend's
        // URL unless a real marketing signal exists (this launch or
        // any prior latched one). An organic user that a generous
        // backend happens to answer with a URL must stay on white.
        val gateOpen = hardSignalOnLaunch || stowage.attributedNonOrganic
        if (!gateOpen) {
            Log.i(
                TAG,
                "decideReturningNative: backend returned URL but no marketing " +
                    "signal — refusing upgrade, staying on Native"
            )
            return Berth.Native
        }
        stowage.course = LastCourse.Web
        return Berth.Web(answer.url!!, fromColdPush = false)
    }

    private suspend fun askChart(installBag: Map<String, Any?>): ChartAnswer {
        val body = JSONObject()

        // Merge priority (best signal first). Semantics: only put a key
        // when body has no NON-EMPTY value there yet, and only when the
        // incoming value is itself non-empty. This lets a later source
        // (referrer) fill in slots that an earlier source (e.g. AF's
        // deep-link callback) left as literal empty strings — which is
        // exactly the failure mode config.php saw on a fresh OneLink
        // install: AF replied `{af_status:Organic, af_message, is_first_launch}`
        // AND the deep-link resolver stubbed every af_sub*/campaign/
        // media_source as `""`, so the real values from Google Play's
        // install referrer never reached the backend.
        //
        //   1. installBag         — SDK conversion payload (authoritative
        //                            for af_status/af_message).
        //   2. campaign.uriFacts  — live VIEW-intent OneLink query params.
        //   3. installReferrer    — Play Store IPC (offline-safe truth
        //                            for the ORIGINAL click that produced
        //                            this install).
        //   4. deepLinkFacts      — AF's deep-link callback (frequently
        //                            arrives with empty stub values).
        //
        // The `pid`/`c`/`af_c_id` short names from the referrer are
        // mirrored to their long aliases (`media_source`/`campaign`/
        // `campaign_id`) so the backend can key off either shape.
        mergeFactsInto(body, installBag)
        mergeFactsInto(body, campaign.uriFacts())
        mergeFactsInto(body, stowage.installReferrerFacts.mapValues { it.value as Any? })
        mergeFactsInto(body, campaign.deepLinkFacts())

        // ── af_status resolution ────────────────────────────────────
        // The backend rejects bodies without an af_status ("No data").
        //
        // AppsFlyer's conversion callback frequently mis-reports Organic
        // on a fresh install even when we have hard evidence of a tracked
        // entry: the OneLink resolver returned pid/campaign/af_sub*, or
        // the app was launched via a VIEW intent carrying the OneLink URL,
        // or the Play Store install referrer already carried the click.
        // This "organic rescue" overrides that: if any hard signal fired,
        // we upgrade the status to Non-organic before hitting config.php.
        //
        // Hard signals (any one is enough):
        //   * OneLink deep-link resolver returned a non-empty payload
        //     containing media_source / campaign / af_c_id / af_sub1-5 /
        //     deep_link_value with a real value.
        //   * The launch URI carried the same fields as query params.
        //   * The Play Store install referrer already carried the click.
        val hardSignal = hasMarketingHardSignal(campaign.deepLinkFacts()) ||
            hasMarketingHardSignal(campaign.uriFacts()) ||
            hasMarketingHardSignal(stowage.installReferrerFacts.mapValues { it.value as Any? })
        val reported = body.optString("af_status").takeIf { it.isNotEmpty() }
        val effective = when {
            hardSignal -> "Non-organic"
            reported != null -> reported
            campaign.wasUriLaunched() -> "Non-organic"
            else -> "Organic"
        }
        body.put("af_status", effective)
        Log.d(
            TAG,
            "af_status resolved to $effective (reported=$reported hardSignal=$hardSignal uri=${campaign.wasUriLaunched()})",
        )

        body.put("af_id", campaign.deviceUid().orEmpty())
        body.put("bundle_id", LinkConfig.APPLICATION_ID)
        body.put("os", "Android")
        body.put("os_version", Build.VERSION.RELEASE)
        body.put("store_id", LinkConfig.STORE_ID)
        body.put("locale", Locale.getDefault().toString().replace('-', '_'))
        body.put("device_model", "${Build.BRAND} ${Build.MODEL}")

        val fcmToken = horn.token
        if (!fcmToken.isNullOrEmpty()) body.put("push_token", fcmToken)
        val firebaseProject = LinkConfig.messagingProject()
        if (firebaseProject.isNotEmpty()) body.put("firebase_project_id", firebaseProject)

        return fetcher.ask(body)
    }

    /**
     * Merge one source of attribution facts into the outgoing JSON body
     * with "keep first non-empty value" semantics. Empty strings and the
     * literal token `"null"` are treated as absent both when reading
     * (they never overwrite existing data) and when writing (we don't
     * put them into the body in the first place).
     *
     * OneLink short-name keys (`pid`, `c`, `af_c_id`, `af_channel`,
     * `af_adset`, `siteid`) are additionally mirrored to their SDK
     * long-name aliases (`media_source`, `campaign`, `campaign_id`,
     * `channel`, `adset`, `af_siteid`) so the backend receives both
     * shapes regardless of which source populated the field.
     */
    private fun mergeFactsInto(body: JSONObject, facts: Map<String, Any?>) {
        if (facts.isEmpty()) return
        for ((key, raw) in facts) {
            if (key.isNullOrBlank()) continue
            val str = raw?.toString().orEmpty()
            if (str.isEmpty() || str.equals("null", ignoreCase = true)) continue
            putIfEmpty(body, key, str)
            KEY_ALIAS[key]?.let { alias -> putIfEmpty(body, alias, str) }
        }
    }

    private fun putIfEmpty(body: JSONObject, key: String, value: String) {
        val existing = body.opt(key)?.toString().orEmpty()
        if (existing.isNotEmpty() && !existing.equals("null", ignoreCase = true)) return
        body.put(key, value)
    }

    /**
     * True if the map carries a non-empty value in ANY of the fields that
     * AppsFlyer only fills when a real tracked click landed. Presence of
     * even one is enough to declare the install non-organic.
     *
     * NOTE: `deep_link_value` and `deep_link_sub1` are INCLUDED here on
     * purpose. On a fresh install right after a OneLink tap, AppsFlyer
     * frequently delivers the deep-link callback with `deep_link_value`
     * populated before the Google Play referrer has landed — so
     * `media_source`/`campaign` may still be empty on that first launch
     * while the deep-link fields already carry the real click payload.
     * Filtering them out here regressed real OneLink taps into the
     * native flow on the tester's release build, so they stay in.
     *
     * BUT: AppsFlyer ships the OneLink template with debug placeholders
     * (`deep_link_value=deep_link_test`, `deep_link_sub1=deep_test_sub1`)
     * that its deep-link resolver delivers on EVERY install regardless
     * of whether a real click landed. Those values are treated as
     * absent here so a fully organic install (per Play referrer) is
     * not misclassified as non-organic just because AF replayed the
     * template defaults.
     */
    private fun hasMarketingHardSignal(facts: Map<String, Any?>): Boolean {
        if (facts.isEmpty()) return false
        return MARKETING_HARD_SIGNAL_KEYS.any { key ->
            val v = facts[key]?.toString().orEmpty()
            v.isNotEmpty() &&
                !v.equals("null", ignoreCase = true) &&
                !isPlaceholderValue(key, v)
        }
    }

    /**
     * AppsFlyer's default OneLink template ships with hard-coded
     * placeholder values that its deep-link resolver echoes back on
     * every install (even organic). We drop them from the hard-signal
     * calculus so an organic install per Play referrer is not flipped
     * into the gray flow purely by SDK boilerplate.
     */
    private fun isPlaceholderValue(key: String, value: String): Boolean {
        return when (key) {
            "deep_link_value" -> value.equals("deep_link_test", ignoreCase = true)
            "deep_link_sub1" -> value.equals("deep_test_sub1", ignoreCase = true)
            else -> false
        }
    }

    private fun launchFireAndForget(): Job = ambientScope.launch {
        runCatching {
            horn.boot()
            val installBag = campaign.awaitInstall(LinkConfig.RETURN_INSTALL_WAIT_MS)
            askChart(installBag)
        }
    }

    companion object {
        private const val TAG = "LinkPilot"

        // Fields that AppsFlyer's OneLink resolver / conversion callback
        // only fills when a real tracked click hit their servers. Any one
        // of them carrying a non-empty value overrides an "Organic" verdict.
        // `deep_link_value` / `deep_link_sub1` are included: on the first
        // launch after a real OneLink tap, AppsFlyer often delivers those
        // fields BEFORE the Google Play referrer resolves media_source /
        // campaign, so they are frequently the only proof of the click
        // that reaches the client in time for the boot decision.
        private val MARKETING_HARD_SIGNAL_KEYS = setOf(
            "media_source", "pid",
            "campaign", "c",
            "campaign_id", "af_c_id",
            "af_adset", "adset",
            "af_ad", "af_ad_id",
            "af_sub1", "af_sub2", "af_sub3", "af_sub4", "af_sub5",
            "af_siteid", "siteid",
            "af_channel",
            "deep_link_value", "deep_link_sub1",
            "agency",
            "af_prt", "af_keywords",
        )

        // OneLink short-param → conversion-callback long-name mirror.
        // Kept in sync with CampaignBroker.URL_ALIAS; duplicated here so
        // askChart can expand aliases coming from the install-referrer
        // map (which carries the SHORT names — `pid`, `c`, `af_c_id`).
        private val KEY_ALIAS = mapOf(
            "pid" to "media_source",
            "c" to "campaign",
            "af_c_id" to "campaign_id",
            "af_channel" to "channel",
            "af_ad" to "ad",
            "af_ad_id" to "ad_id",
            "af_adset_id" to "adset_id",
            "af_adset" to "adset",
            "af_ad_type" to "ad_type",
            "af_keywords" to "keywords",
            "siteid" to "af_siteid",
        )

        @Volatile
        private var singleton: LinkPilot? = null
        private val lock = Any()

        fun of(context: Context): LinkPilot {
            singleton?.let { return it }
            return synchronized(lock) {
                singleton ?: build(context.applicationContext).also { singleton = it }
            }
        }

        private fun build(ctx: Context): LinkPilot {
            val app = ctx.applicationContext as Application
            val stowage = StowageBox(app)
            val auditor = LinkAuditor(app)
            val campaign = CampaignBroker(app)
            val fetcher = ChartFetcher(stowage)
            val horn = HornService(app)
            val referrer = InstallReferrerProbe(app)
            return LinkPilot(stowage, auditor, campaign, fetcher, horn, referrer)
        }
    }
}
