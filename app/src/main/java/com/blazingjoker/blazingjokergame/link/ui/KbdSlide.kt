package com.blazingjoker.blazingjokergame.link.ui

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Keeps the focused input in a `WebCanvasActivity` clear of the software
 * keyboard by sliding the WebView with `translationY` in step with the IME
 * animation. Shrinking the WebView would force Chromium to re-lay out the
 * page and then double-scroll the focused element — the two corrections
 * disagree by a handful of pixels and the field appears to "run away" from
 * the caret. Here the WebView keeps its full height and the compositor
 * moves it; Chromium is fed IME insets of zero so it never scrolls on the
 * keyboard's behalf.
 *
 * A tiny JS bridge injected on every `onPageFinished` reports the focused
 * field's on-screen rectangle back to Kotlin through [FieldSpy.report];
 * the lift is derived from that rectangle plus the keyboard height so the
 * field ends up just above the keyboard with a small breathing gap in
 * either orientation.
 */
internal class KbdSlide(
    private val root: View,
    /**
     * Fired at the start and end of every IME animation. The host uses it
     * to re-apply its immersive flags — Android's default keyboard opening
     * transiently reveals the status bar, and the activity insists on
     * staying fullscreen through the transition.
     */
    private val onImeChange: () -> Unit = {},
) {

    private var page: WebView? = null

    // Focused-field rectangle, expressed in device pixels relative to the
    // root view. `topPx == -1f` means "no field is currently focused".
    private var topPx = -1f
    private var footPx = -1f
    private var inFrame = false

    // Current keyboard height being ridden. Zero when the keyboard is
    // closed or in the process of closing past zero.
    private var kbdPx = 0
    private var animating = false

    // The system reports two useful upper bounds on the keyboard: the
    // declared upper bound (right the first time an orientation is used,
    // wrong afterwards) and the height an earlier opening actually rested
    // at (right every time it is available). Prefer the latter.
    private var declaredMax = 0
    private var restingMax = 0

    private val poke = Runnable {
        page?.evaluateJavascript("window.${TAG}Ping && window.${TAG}Ping();", null)
    }

    fun attach() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            if (!animating) {
                observe(insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
                slide(smooth = kbdPx > 0)
                if (kbdPx > 0) pokeLater()
            }
            eraseImeFromChromium(insets)
        }

        ViewCompat.setWindowInsetsAnimationCallback(
            root,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    if (isKeyboardAnim(animation)) {
                        animating = true
                        onImeChange()
                    }
                }

                override fun onStart(
                    animation: WindowInsetsAnimationCompat,
                    bounds: WindowInsetsAnimationCompat.BoundsCompat,
                ): WindowInsetsAnimationCompat.BoundsCompat {
                    if (isKeyboardAnim(animation)) declaredMax = bounds.upperBound.bottom
                    return bounds
                }

                override fun onProgress(
                    state: WindowInsetsCompat,
                    inFlight: MutableList<WindowInsetsAnimationCompat>,
                ): WindowInsetsCompat {
                    if (animating) {
                        rideTo(state.getInsets(WindowInsetsCompat.Type.ime()).bottom)
                        // Assigned, never animated — the WebView has to
                        // move in step with the keyboard, not chase it a
                        // frame behind.
                        slide(smooth = false)
                    }
                    return state
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    if (!isKeyboardAnim(animation)) return
                    animating = false
                    declaredMax = 0
                    ViewCompat.requestApplyInsets(root)
                    onImeChange()
                    if (kbdPx > 0) pokeLater() else slide(smooth = false)
                }

                private fun isKeyboardAnim(animation: WindowInsetsAnimationCompat) =
                    animation.typeMask and WindowInsetsCompat.Type.ime() != 0
            },
        )

        ViewCompat.requestApplyInsets(root)
    }

    @SuppressLint("JavascriptInterface")
    fun follow(view: WebView) {
        page = view
        forgetField()
        view.translationY = 0f
        view.addJavascriptInterface(FieldSpy(), BRIDGE)
    }

    fun forgetField() {
        root.removeCallbacks(poke)
        topPx = -1f
        footPx = -1f
        inFrame = false
        slide(smooth = false)
    }

    fun reorient() {
        forgetField()
        restingMax = 0
        if (kbdPx > 0) pokeLater()
    }

    private fun pokeLater() {
        root.removeCallbacks(poke)
        root.postDelayed(poke, SETTLE_MS)
    }

    private fun observe(height: Int) {
        kbdPx = height
        if (height <= 0 || height == restingMax) return
        restingMax = height
    }

    private fun rideTo(height: Int) {
        val ceiling = if (restingMax > 0) restingMax else declaredMax
        kbdPx = if (ceiling > 0) minOf(height, ceiling) else height
    }

    @Suppress("unused")
    private fun portrait(): Boolean =
        root.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE

    /**
     * Rebuild the incoming insets with IME zeroed out. If Chromium sees a
     * real IME inset it will haul the page up by an amount that varies
     * from one opening to the next on the same field, which fights the
     * translationY slide.
     */
    private fun eraseImeFromChromium(arriving: WindowInsetsCompat): WindowInsetsCompat =
        runCatching {
            val ime = WindowInsetsCompat.Type.ime()
            WindowInsetsCompat.Builder(arriving)
                .setInsets(ime, Insets.NONE)
                .setVisible(ime, false)
                .build()
        }.getOrDefault(arriving)

    private fun slide(smooth: Boolean) {
        val view = page ?: return
        val target = -lift(view)
        view.animate().cancel()
        if (smooth && view.translationY != target) {
            view.animate().translationY(target).setDuration(SLIDE_MS).start()
        } else {
            view.translationY = target
        }
    }

    private fun lift(view: View): Float {
        val height = kbdPx
        val span = view.height
        if (height <= 0 || span <= 0 || footPx < 0f) return 0f

        val aimAt: Float
        val most: Float
        if (inFrame) {
            // Somewhere in that iframe is the real editable, but its
            // rectangle is not readable across origins — aim at the
            // iframe's own foot but never so far that its head leaves
            // the screen (the caret might be right up there).
            aimAt = footPx
            most = minOf(height.toFloat(), maxOf(0f, topPx))
        } else {
            // A tall field is aimed at by its head, because that is
            // where the caret sits. Hauling a long field up by its foot
            // takes its head off screen.
            aimAt = minOf(
                footPx,
                topPx + HEAD_ROOM_DP * view.resources.displayMetrics.density,
            )
            most = height.toFloat()
        }
        return (aimAt - (span - height)).coerceIn(0f, most)
    }

    private inner class FieldSpy {
        @JavascriptInterface
        fun report(framed: Boolean, top: Double, foot: Double) {
            val view = page ?: return
            view.post {
                inFrame = framed
                topPx = top.toFloat()
                footPx = foot.toFloat()
                if (kbdPx > 0) slide(smooth = !animating)
            }
        }
    }

    /**
     * Asks the page where the focused field is, in device pixels. Never as
     * a share of the viewport — the viewport is the one thing that can
     * change out from under the measurement. Idempotent per document via
     * the [TAG] guard.
     */
    val probe: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        """
        (function(){
          if (window.$TAG) return;
          window.$TAG = true;

          var typable = function(node){
            if (!node) return false;
            if (node.tagName === 'INPUT') {
              var kind = (node.type || 'text').toLowerCase();
              return ['checkbox','radio','button','submit','reset','file','range','image','color']
                       .indexOf(kind) === -1;
            }
            return node.tagName === 'TEXTAREA' || node.isContentEditable === true;
          };

          var rectOf = function(node, view){
            if (node.isContentEditable) {
              try {
                var picked = view.getSelection();
                if (picked && picked.rangeCount) {
                  var caret = picked.getRangeAt(0).getBoundingClientRect();
                  if (caret && caret.height > 0) return caret;
                }
              } catch (e) {}
            }
            return node.getBoundingClientRect();
          };

          var listen = function(doc){
            try {
              if (!doc || doc.${TAG}Seen) return;
              doc.${TAG}Seen = true;
              doc.addEventListener('focusin', bump, true);
            } catch (e) {}
          };

          var find = function(){
            var node = document.activeElement, view = window, shift = 0, depth = 0;
            while (node && (node.tagName === 'IFRAME' || node.tagName === 'FRAME') && depth++ < 4) {
              var frame = node.getBoundingClientRect(), doc = null;
              try { doc = node.contentDocument; } catch (e) { doc = null; }
              var inner = doc ? doc.activeElement : null;
              if (!inner || inner === doc.body) {
                return { framed: true, top: shift + frame.top, foot: shift + frame.bottom };
              }
              listen(doc);
              view = node.contentWindow || view;
              shift += frame.top;
              node = inner;
            }
            if (!typable(node)) return null;
            var box = rectOf(node, view);
            return { framed: false, top: shift + box.top, foot: shift + box.bottom };
          };

          var tell = function(){
            var spot = find();
            if (!spot) return;
            var visual = window.visualViewport;
            var raised = visual ? visual.offsetTop : 0;
            var zoom = (visual && visual.scale) ? visual.scale : 1;
            var scale = (window.devicePixelRatio || 1) * zoom;
            try {
              $BRIDGE.report(spot.framed,
                             (spot.top - raised) * scale,
                             (spot.foot - raised + $BREATH_CSS) * scale);
            } catch (e) {}
          };

          var bump = function(){ tell(); setTimeout(tell, 200); };

          var pending = false;
          var soon = function(){
            if (pending) return;
            pending = true;
            var go = function(){ pending = false; tell(); };
            if (window.requestAnimationFrame) requestAnimationFrame(go); else setTimeout(go, 16);
          };
          if (window.visualViewport) {
            window.visualViewport.addEventListener('resize', soon);
            window.visualViewport.addEventListener('scroll', soon);
          }

          window.${TAG}Ping = tell;
          listen(document);
        })();
        """.trimIndent()
    }

    private companion object {
        // Fixed obscure identifiers unlikely to collide with any page
        // scripting. Renamed away from magma-coins to keep the fingerprint
        // distinct across sibling apps.
        const val TAG = "_bjKbdA9"
        const val BRIDGE = "_bjKbdBridge"

        // Breathing room under the field, in CSS pixels.
        const val BREATH_CSS = 10

        // How much of a tall field has to stay visible for typing to be
        // useful.
        const val HEAD_ROOM_DP = 96f

        // Grace for the page to finish any of its own layout before it is
        // read.
        const val SETTLE_MS = 140L

        const val SLIDE_MS = 160L
    }
}
