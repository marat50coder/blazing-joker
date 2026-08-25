package com.blazingjoker.blazingjokergame.link.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.blazingjoker.blazingjokergame.link.config.LinkConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress

/**
 * Connectivity + DNS reachability. `ConnectivityManager` alone reports
 * "connected" during captive portals and during the first few hundred ms
 * of a VPN bring-up, so we layer a real DNS lookup on top before committing
 * to the online branch.
 *
 * The probe host list rotates internally between two candidates on each
 * successful lookup — a temporarily unresolvable host does not stick us
 * on the offline branch. Never probe the chart endpoint or a partner host
 * (a) it would log traffic before the chart POST fires, and (b) it would
 * create a probe → own-host correlation in packet captures.
 */
internal class LinkAuditor(context: Context) {

    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    // Two well-known DNS hosts unrelated to the partner or chart endpoint.
    // Rotate per project (fingerprint) — pick from major CDN roots.
    private val probes = listOf("captive.apple.com", "cloudflare.com")
    private var probeCursor = 0

    /**
     * True if at least one network transport is up. Includes VPN and
     * Bluetooth — dropping either has produced false offline verdicts on
     * real user traces.
     */
    fun hasCarrier(): Boolean {
        val caps = activeCaps() ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH))
    }

    /**
     * Stricter than [hasCarrier]: also requires Android's own network
     * validation to have succeeded (NET_CAPABILITY_VALIDATED). Android
     * probes `/generate_204` on the transport after association; the flag
     * stays false when the AP is up but the upstream is blocked (captive
     * portal without login, disabled mobile data plan, dead-ISP wifi).
     *
     * This is the fast-path signal for the boot dispatcher: if we don't
     * have a truly usable internet path, running the pilot burns 12+ s
     * on DNS probes and 26 s on AppsFlyer's install-wait for a verdict
     * that can only be `LostSignal`. Short-circuit to the offline berth
     * instead — from the user's seat, "no internet" opens exactly the
     * same screen either way, just 40 s sooner.
     *
     * We deliberately do NOT use this inside [canRouteOut]: the pilot
     * already tolerates the DNS wait and some Android forks (Xiaomi,
     * Realme on certain SIMs) delay setting VALIDATED even when the
     * upstream works, so relying on it there would false-negative real
     * internet on a slow-cold-validate cycle.
     */
    fun hasValidatedInternet(): Boolean {
        if (!hasCarrier()) return false
        val caps = activeCaps() ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun activeCaps(): NetworkCapabilities? {
        val m = cm ?: return null
        val active = m.activeNetwork ?: return null
        return m.getNetworkCapabilities(active)
    }

    /** True if we can resolve at least one rotating probe host in time. */
    suspend fun canRouteOut(): Boolean {
        if (!hasCarrier()) return false
        return withContext(Dispatchers.IO) {
            for (i in probes.indices) {
                val host = probes[(probeCursor + i) % probes.size]
                val ok = withTimeoutOrNull(LinkConfig.DNS_PROBE_TIMEOUT_MS) {
                    runCatching {
                        val answer = InetAddress.getAllByName(host)
                        answer.isNotEmpty()
                    }.getOrDefault(false)
                }
                if (ok == true) {
                    probeCursor = (probeCursor + 1) % probes.size
                    return@withContext true
                }
            }
            false
        }
    }
}
