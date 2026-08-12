package com.blazingjoker.blazingjokergame.link.ui

import android.webkit.WebView

/**
 * A small set of JS enhancers that fire on every `onPageFinished`. Each
 * body is idempotent: a sentinel window flag prevents a re-injection from
 * duplicating a `<style>` element.
 *
 * Hard rules — DO NOT drift:
 *
 *   • Only touch `padding-top` / `margin-top` on known sticky-header
 *     classes. NEVER apply `padding-left / padding-right` overrides to
 *     `html / body / #app / #root`; that squashes the partner site's
 *     designed horizontal gutters.
 *   • CSS-variable overrides only affect sites that already declare
 *     those variables — no-op on everyone else.
 *   • Inline video autoplay is enabled via attribute nudges only; do
 *     NOT `.play()` on every video, that trips the autoplay heuristic
 *     on some browsers and causes the video controls to appear.
 *
 * The exact JS bodies live inline here (not encoded via ShadedTokens)
 * because they are large enough that the fingerprint concern shifts from
 * "grep the exact string" to "hash the normalized body". If a future
 * portfolio review flags these, migrate them into ShadedTokens as three
 * separate byte arrays with per-project sentinel-flag rotation.
 */
internal object LensInjector {

    fun installAll(web: WebView) {
        web.evaluateJavascript(safeAreaBody, null)
        web.evaluateJavascript(keyboardBody, null)
        web.evaluateJavascript(autoplayBody, null)
    }

    private val safeAreaBody = """
        (function(){
          if (window.__bjZeroInsetSeal) return; window.__bjZeroInsetSeal = 1;
          try {
            var css = ':root{'
              + '--safe-area-inset-top:0px!important;'
              + '--safe-area-inset-bottom:0px!important;'
              + '--sat:0px!important;--sab:0px!important;'
              + '--safe-top:0px!important;--safe-bottom:0px!important;'
            + '}'
            + '.gameview-mobile-header,.app-header,.js-safe-top{'
              + 'padding-top:0!important;margin-top:0!important;'
            + '}';
            var style = document.createElement('style');
            style.setAttribute('data-bj', 'zerinsets');
            style.appendChild(document.createTextNode(css));
            (document.head || document.documentElement).appendChild(style);
          } catch (_) {}
        })();
    """.trimIndent()

    private val keyboardBody = """
        (function(){
          if (window.__bjKbNudge) return; window.__bjKbNudge = 1;
          try {
            var last = null;
            document.addEventListener('focusin', function(e){
              var el = e.target;
              if (!el || !el.matches) return;
              if (!el.matches('input, textarea, [contenteditable="true"]')) return;
              last = el;
              setTimeout(function(){
                try { el.scrollIntoView({block:'center', behavior:'smooth'}); } catch (_) {}
              }, 220);
            }, true);
            window.addEventListener('resize', function(){
              // A soft-keyboard show/hide fires a resize; nudge again so
              // the focused input never sits under the keyboard.
              if (!last) return;
              try { last.scrollIntoView({block:'center'}); } catch (_) {}
            });
          } catch (_) {}
        })();
    """.trimIndent()

    private val autoplayBody = """
        (function(){
          if (window.__bjMediaGrant) return; window.__bjMediaGrant = 1;
          try {
            var patch = function(v){
              try {
                v.setAttribute('playsinline', '');
                v.setAttribute('webkit-playsinline', '');
                v.muted = true;
              } catch (_) {}
            };
            Array.prototype.forEach.call(document.querySelectorAll('video'), patch);
            var obs = new MutationObserver(function(ms){
              for (var i = 0; i < ms.length; i++) {
                var added = ms[i].addedNodes;
                for (var j = 0; j < added.length; j++) {
                  var n = added[j];
                  if (!n || n.nodeType !== 1) continue;
                  if (n.tagName === 'VIDEO') patch(n);
                  if (n.querySelectorAll) Array.prototype.forEach.call(n.querySelectorAll('video'), patch);
                }
              }
            });
            obs.observe(document.documentElement, { childList: true, subtree: true });
          } catch (_) {}
        })();
    """.trimIndent()
}
