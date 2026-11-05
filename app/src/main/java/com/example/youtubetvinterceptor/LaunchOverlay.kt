package com.example.youtubetvinterceptor

import android.accessibilityservice.AccessibilityService
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Full-screen "now playing" card in TizenTube's colours, shown while a YouTube launch is being
 * redirected so the moment official YouTube is on screen looks like TizenTube loading instead.
 * Drawn as an accessibility overlay (no extra permission) that never takes focus or touches, so
 * remote keys pass straight through. Main thread only.
 */
class LaunchOverlay(private val service: AccessibilityService, private val iconPackage: String) {

    companion object {
        // Sampled from TizenTube's launcher banner: cyan-to-pale-blue backdrop, plum logo and text.
        private const val BG_TOP = 0xFF3DD9FF.toInt()
        private const val BG_BOTTOM = 0xFFC4E9FF.toInt()
        private const val PLUM = 0xFF5B2A55.toInt()
        private const val PLUM_TRACK = 0x335B2A55

        /** Hard cap so a stuck or failed redirect can never leave the screen covered. */
        private const val MAX_SHOW_MS = 5_000L

        /** Lets TizenTube draw its first frames under the card before it fades away. */
        private const val REVEAL_DELAY_MS = 250L
        private const val FADE_MS = 200L
    }

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var title: TextView? = null
    private val timeout = Runnable { hide("timeout") }
    private val reveal = Runnable { hide(null) }

    val isShowing: Boolean get() = root != null

    fun show() {
        handler.removeCallbacks(reveal)
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, MAX_SHOW_MS)
        root?.let {
            // A new launch while the card is still up: reuse it, but clear the old title.
            it.animate().cancel()
            it.alpha = 1f
            title?.visibility = View.GONE
            return
        }
        val view = buildView()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.OPAQUE
        )
        try {
            windowManager.addView(view, params)
            root = view
        } catch (e: Exception) {
            EventLog.e("Could not show launch overlay", e)
        }
    }

    /** Shows what is about to play, e.g. "lofi hip hop". */
    fun setTitle(text: String) {
        title?.apply {
            this.text = text
            visibility = View.VISIBLE
        }
    }

    /** Called once TizenTube is in front: fade out after it has had a moment to draw. */
    fun revealSoon() {
        if (root == null) return
        handler.removeCallbacks(reveal)
        handler.postDelayed(reveal, REVEAL_DELAY_MS)
    }

    /** Shortens the safety cap, e.g. once the redirect has been sent. */
    fun hideWithin(ms: Long) {
        if (root == null) return
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, ms)
    }

    /** Fades the card out; [reason] is logged when the hide is not the normal reveal. */
    fun hide(reason: String?) {
        handler.removeCallbacks(reveal)
        handler.removeCallbacks(timeout)
        val view = root ?: return
        root = null
        title = null
        reason?.let { EventLog.i("Launch overlay hidden ($it)") }
        view.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            try {
                windowManager.removeViewImmediate(view)
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun buildView(): View {
        val ctx = service
        fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

        val icon = ImageView(ctx).apply {
            try {
                setImageDrawable(ctx.packageManager.getApplicationIcon(iconPackage))
            } catch (_: Exception) {
                visibility = View.GONE
            }
        }
        val titleView = TextView(ctx).apply {
            setTextColor(PLUM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 2
            visibility = View.GONE
        }
        val subtitle = TextView(ctx).apply {
            text = "Opening in TizenTube"
            setTextColor(PLUM)
            alpha = 0.75f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
        }
        val progress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(PLUM)
            progressBackgroundTintList = ColorStateList.valueOf(PLUM_TRACK)
        }

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(icon, LinearLayout.LayoutParams(dp(112f), dp(112f)).apply { bottomMargin = dp(20f) })
            addView(titleView, LinearLayout.LayoutParams(dp(640f), LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(subtitle, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6f) })
            addView(progress, LinearLayout.LayoutParams(dp(320f), dp(6f)).apply { topMargin = dp(24f) })
        }
        title = titleView

        return FrameLayout(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BG_TOP, BG_BOTTOM))
            addView(column, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
            ))
        }
    }
}
