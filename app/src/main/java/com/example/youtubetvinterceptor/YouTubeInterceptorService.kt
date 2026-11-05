package com.example.youtubetvinterceptor

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/**
 * AccessibilityService that redirects Google Assistant / deep-link launches of the
 * official YouTube TV client to TizenTube Cobalt.
 *
 * Flow:
 *  1. Tail `logcat ActivityTaskManager:I` (system buffer) for START lines targeting
 *     official YouTube.
 *  2. On a hit, read the unredacted dat= URL from `dumpsys activity starter` (falling back
 *     to a matching record in `dumpsys activity activities`); never guess a stale one.
 *  3. Wait for YouTube's ShellActivity -> MainActivity hop (slow on a cold start), then
 *     launch TizenTube Cobalt with ACTION_VIEW + that URL so it lands on top.
 *  4. Meanwhile a [LaunchOverlay] card covers the screen from detection until TizenTube's
 *     window is in front, hiding the brief moment official YouTube is visible.
 *
 * Official YouTube is left in RAM: on this TV killBackgroundProcesses is refused for it
 * (a system app), and it drops to a cached process that Android reclaims as needed.
 * It must stay enabled: when disabled, the Assistant sends you to the Play Store instead.
 *
 * Requires these granted via adb (they cannot be granted through the UI):
 *   adb shell pm grant com.example.youtubetvinterceptor android.permission.READ_LOGS
 *   adb shell pm grant com.example.youtubetvinterceptor android.permission.DUMP
 *   adb shell pm grant com.example.youtubetvinterceptor android.permission.PACKAGE_USAGE_STATS
 * and the service enabled under Settings > Accessibility.
 */
class YouTubeInterceptorService : AccessibilityService() {

    companion object {
        private const val OFFICIAL_YT = "com.google.android.youtube.tv"
        private const val TIZEN_PKG = "io.gh.reisxd.tizentube.cobalt"
        private const val TIZEN_ACTIVITY = "dev.cobalt.app.MainActivity"

        /** Google Assistant on Google TV; its "play"/"search" launches always end up in TizenTube. */
        private const val ASSISTANT_PKG = "com.google.android.katniss"

        /** Stands in for a URL when TizenTube should just be opened (no link to hand over). */
        private const val TIZEN_HOME = "tizentube:home"

        /** A START line in ActivityTaskManager that references the official YouTube component. */
        private val START_LINE: Pattern = Pattern.compile(
            "START u0 .*(?:pkg|cmp)=" + Pattern.quote(OFFICIAL_YT)
        )

        /** A one-line dumpsys Intent that targets official YouTube. */
        private val YT_TARGET: Pattern = Pattern.compile("\\b(?:pkg|cmp)=" + Pattern.quote(OFFICIAL_YT))

        /**
         * The data URL of a one-line dumpsys Intent (group 1). It runs up to the next Intent
         * field, because the Assistant's search URLs carry raw JSON, braces and spaces included:
         * `https://www.youtube.com/tv?voice={"youtubeAssistantRequest":{"query":"search for …"}}&…`
         */
        private val DAT_URL: Pattern = Pattern.compile(
            "\\bdat=(https?://.+?)(?: (?:typ|flg|pkg|cmp)=| \\(has | \\}\\s*$)"
        )

        /** Flags, component and caller uid of a START line, used to pick its intent out of dumpsys. */
        private val START_FLG: Pattern = Pattern.compile("\\bflg=(0x[0-9a-fA-F]+)")
        private val START_CMP: Pattern = Pattern.compile("\\bcmp=([^\\s}]+)")
        private val START_UID: Pattern = Pattern.compile("\\bfrom uid (\\d+)")

        /** The data URL of a one-line dumpsys Intent that targets official YouTube, or null. */
        private fun youTubeIntentUrl(line: String): String? {
            if (!YT_TARGET.matcher(line).find()) return null
            val m = DAT_URL.matcher(line)
            return if (m.find()) m.group(1) else null
        }

        /** START lines within this window of a launch are treated as that launch's own hops. */
        private const val DEBOUNCE_MS = 5000L

        /**
         * Max wait for YouTube's ShellActivity -> MainActivity hop before redirecting anyway.
         * The hop takes ~20ms when YouTube is warm but over a second on a cold start, and if it
         * lands after our redirect YouTube ends up on top of TizenTube.
         */
        private const val HOP_TIMEOUT_MS = 3000L

        /** Small delay after the hop's START line so our launch lands on top of it. */
        private const val HOP_SETTLE_MS = 50L

        /** How long after a redirect official YouTube's playback is stopped if it starts. */
        private const val YT_GUARD_MS = 15_000L

        /** When to record which activity is in front after a redirect, for diagnosing issues. */
        private const val FOREGROUND_CHECK_MS = 5_000L

        /**
         * Max time the launch overlay stays up after the redirect is sent, in case TizenTube was
         * already in front and no window change is reported.
         */
        private const val OVERLAY_AFTER_REDIRECT_MS = 2_500L

        /** The resumed (foreground) activity in `dumpsys activity activities`. */
        private val RESUMED_ACTIVITY: Pattern =
            Pattern.compile("mResumedActivity: ActivityRecord\\{\\S+ u\\d+ (\\S+)")

        /**
         * What the user asked for, for the overlay: "play lofi hip hop on YouTube" ->
         * "lofi hip hop", "search for Minecraft videos" -> "Minecraft videos".
         */
        private fun spokenTitle(url: String): String? {
            val vq = try {
                Uri.parse(url).getQueryParameter("vq")
            } catch (e: Exception) {
                null
            } ?: return null
            return vq.trim().replace(Regex("\\s+"), " ")
                .replace(Regex("^(?:play|search(?:\\s+for)?)\\s+", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s+on youtube$", RegexOption.IGNORE_CASE), "")
                .takeIf { it.isNotBlank() }
        }
    }

    @Volatile private var running = false
    private var logcatThread: Thread? = null
    @Volatile private var logcatProcess: Process? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Uptime of the last new launch; only touched on the logcat watcher thread. */
    private var lastLaunchAt = 0L
    private val launchSeq = AtomicInteger()

    /**
     * One YouTube launch being redirected. The redirect fires once both the URL lookup has
     * finished and YouTube's ShellActivity -> MainActivity hop has been seen, whichever
     * comes last. Only touched on the main thread.
     */
    private class Launch(val id: Int, var hopSeen: Boolean) {
        var url: String? = null
        var redirected = false
        var hopTimeout: Runnable? = null
    }

    private var current: Launch? = null

    private val overlay by lazy { LaunchOverlay(this, TIZEN_PKG) }
    private val pins by lazy { PinStore(this) }

    private val assistantUid: Int? by lazy {
        try {
            packageManager.getPackageUid(ASSISTANT_PKG, 0)
        } catch (e: Exception) {
            EventLog.w("$ASSISTANT_PKG not found; unreadable Assistant links won't fall back to TizenTube")
            null
        }
    }

    private val sessionManager by lazy { getSystemService(MediaSessionManager::class.java) }
    private val listenerComponent by lazy { ComponentName(this, MediaListenerService::class.java) }
    private var sessionsListenerRegistered = false

    /** Uptime until which official YouTube's playback is stopped; main thread only. */
    private var guardUntil = 0L
    private val guardedCallbacks = mutableMapOf<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        controllers?.let { stopYouTubeIn(it, "YouTube session became active") }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        EventLog.init(this)
        EventLog.i("Service connected; starting logcat watcher")
        // Load (or create) pins.txt now so a bad edit shows up in the log before it's needed.
        Thread({ pins.lookup("") }, "pins-load").start()
        startLogcatWatcher()
    }

    private fun startLogcatWatcher() {
        if (running) return
        running = true
        logcatThread = Thread {
            while (running) {
                try {
                    // Start from "now" so we skip the backlog without clearing the device log.
                    val since = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

                    // ActivityTaskManager START lines are written to the system buffer.
                    val proc = Runtime.getRuntime().exec(
                        arrayOf(
                            "logcat", "-b", "system", "-b", "main", "-v", "brief", "-T", since,
                            "ActivityTaskManager:I", "*:S"
                        )
                    )
                    logcatProcess = proc

                    BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                        var line: String?
                        while (running) {
                            line = reader.readLine() ?: break
                            val l = line ?: continue
                            if (START_LINE.matcher(l).find()) {
                                EventLog.i("Detected YouTube launch: $l")
                                handleLaunchDetected(l)
                            }
                        }
                    }
                } catch (e: Exception) {
                    EventLog.e("logcat watcher error", e)
                }
                // Stream ended but we're still alive: back off, then restart.
                if (running) {
                    try {
                        Thread.sleep(1000L)
                    } catch (ie: InterruptedException) {
                        break
                    }
                }
            }
            EventLog.i("logcat watcher stopped")
        }.apply {
            isDaemon = true
            name = "yt-logcat-watcher"
            start()
        }
    }

    /** Called on the logcat watcher thread for every START line targeting official YouTube. */
    private fun handleLaunchDetected(startLine: String) {
        // YouTube's ShellActivity trampolines into MainActivity, producing a second START line.
        // Treat it as part of the launch we're handling: don't re-read dumpsys (it can pick up a
        // stale intent from an older YouTube task), just make sure TizenTube ends up on top.
        val now = SystemClock.uptimeMillis()
        if (now - lastLaunchAt < DEBOUNCE_MS) {
            mainHandler.post { onFollowUpStart() }
            return
        }
        lastLaunchAt = now
        val id = launchSeq.incrementAndGet()
        val viaShell = startLine.contains("ShellActivity")
        // Only deep links (a dat= URL: "play …", "search for …") get redirected; "open YouTube"
        // and opening it from the launcher carry no link and are left alone.
        val isDeepLink = startLine.contains(" dat=")
        val fromAssistant = START_UID.matcher(startLine)
            .let { it.find() && it.group(1).toInt() == assistantUid }
        mainHandler.post { beginLaunch(id, viaShell, isDeepLink) }

        // dumpsys takes 200-400 ms on this TV. Run it off the watcher thread so the hop's START
        // line is seen as soon as it is logged rather than queued behind the lookup.
        Thread({
            val raw = if (isDeepLink) extractUrlFromDumpsys(startLine) else null
            val url = when {
                raw != null -> applyPin(raw)
                // The Assistant asked for something but its link is unreadable (e.g. the launcher's
                // wake-up reset overwrote it): TizenTube is still where the user wants to be.
                isDeepLink && fromAssistant -> {
                    EventLog.i("Assistant link unreadable; opening TizenTube's home screen instead")
                    TIZEN_HOME
                }
                else -> null
            }
            val title = raw?.let { spokenTitle(it) }
            mainHandler.post { onUrlResolved(id, url, title) }
        }, "yt-url-lookup").start()
    }

    private fun beginLaunch(id: Int, viaShell: Boolean, isDeepLink: Boolean) {
        current?.hopTimeout?.let { mainHandler.removeCallbacks(it) }
        if (isDeepLink) overlay.show()
        // Only a ShellActivity launch hops; anything else can redirect as soon as the URL is known.
        val launch = Launch(id, hopSeen = !viaShell)
        current = launch
        if (viaShell) {
            EventLog.i("Waiting for YouTube's ShellActivity hop before redirecting")
            val timeout = Runnable {
                if (!launch.hopSeen) {
                    EventLog.w("No YouTube hop within ${HOP_TIMEOUT_MS}ms; redirecting anyway")
                    launch.hopSeen = true
                    maybeRedirect(launch)
                }
            }
            launch.hopTimeout = timeout
            mainHandler.postDelayed(timeout, HOP_TIMEOUT_MS)
        }
    }

    private fun onFollowUpStart() {
        val launch = current ?: return
        when {
            !launch.hopSeen -> {
                EventLog.i("YouTube hop detected")
                launch.hopSeen = true
                maybeRedirect(launch)
            }
            launch.redirected -> {
                // YouTube came to the front again after we redirected (e.g. a hop that arrived
                // after the timeout): put TizenTube back on top.
                val url = launch.url ?: return
                EventLog.i("YouTube came to front again; redirecting on top of it")
                mainHandler.postDelayed({ redirectTo(url) }, HOP_SETTLE_MS)
            }
            // Otherwise the hop was already seen and the URL lookup is still running;
            // maybeRedirect fires when it lands.
        }
    }

    private fun onUrlResolved(id: Int, url: String?, title: String?) {
        val launch = current ?: return
        if (launch.id != id) return
        if (url == null) {
            launch.hopTimeout?.let { mainHandler.removeCallbacks(it) }
            EventLog.i("Nothing to redirect; leaving YouTube alone")
            overlay.hide("no redirect")
            return
        }
        title?.let { overlay.setTitle(it) }
        launch.url = url
        maybeRedirect(launch)
    }

    private fun maybeRedirect(launch: Launch) {
        val url = launch.url
        if (launch.redirected || url == null || !launch.hopSeen) return
        launch.redirected = true
        launch.hopTimeout?.let { mainHandler.removeCallbacks(it) }
        mainHandler.postDelayed({ redirectTo(url) }, HOP_SETTLE_MS)
    }

    /**
     * Finds the URL of the launch described by [startLine]. The YouTube task keeps older
     * intents around (its root intent is whatever first opened it), so only an intent that
     * provably belongs to this launch is used; otherwise we return null and let YouTube play
     * rather than redirect to a stale video.
     */
    private fun extractUrlFromDumpsys(startLine: String): String? {
        return try {
            // 1. The activity starter's most recent start keeps its full intent even after the
            //    ShellActivity trampoline finishes. At detection time that is this launch (or its
            //    own trampoline hop); only trusted if it targeted official YouTube.
            val starter = dumpsys("starter")
            val intentLine = starter.substringAfter("mStartActivity:", "")
                .lineSequence().firstOrNull { it.trimStart().startsWith("Intent {") }
            intentLine?.let { youTubeIntentUrl(it) }?.let { if (!it.endsWith("/...")) return it }

            // 2. The launched activity's own record, while it is still alive. dumpsys may add
            //    flags of its own, so this only matches when they happen to be unchanged.
            val wantFlg = START_FLG.matcher(startLine).let { if (it.find()) it.group(1) else null }
            val wantCmp = START_CMP.matcher(startLine).let { if (it.find()) it.group(1) else null }
            if (wantFlg != null && wantCmp != null) {
                for (line in dumpsys("activities").lineSequence()) {
                    if (!line.trimStart().startsWith("Intent {")) continue
                    if (!line.contains("cmp=$wantCmp") || !line.contains("flg=$wantFlg ")) continue
                    val url = youTubeIntentUrl(line) ?: continue
                    EventLog.i("Starter record unusable; using matching activity record")
                    return url
                }
            }

            EventLog.w("Could not find this launch's intent (flg=$wantFlg cmp=$wantCmp); not redirecting")
            null
        } catch (e: Exception) {
            EventLog.e("dumpsys read failed", e)
            null
        }
    }

    /** Swaps in a pinned URL when the Assistant's heard query (`vq`) is listed in pins.txt. */
    private fun applyPin(url: String): String {
        val vq = try {
            Uri.parse(url).getQueryParameter("vq")
        } catch (e: Exception) {
            null
        } ?: return url
        val pinned = pins.lookup(vq) ?: return url
        EventLog.i("Pinned query \"$vq\" -> $pinned (Assistant sent $url)")
        return pinned
    }

    private fun dumpsys(section: String): String {
        val proc = Runtime.getRuntime().exec(arrayOf("dumpsys", "activity", section))
        val output = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
        proc.waitFor()
        return output
    }

    private fun redirectTo(url: String) {
        val intent = if (url == TIZEN_HOME) {
            EventLog.i("Opening TizenTube")
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                component = ComponentName(TIZEN_PKG, TIZEN_ACTIVITY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            EventLog.i("Redirecting to TizenTube: $url")
            Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(url)
                component = ComponentName(TIZEN_PKG, TIZEN_ACTIVITY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
        mainHandler.post {
            try {
                startActivity(intent)
                overlay.hideWithin(OVERLAY_AFTER_REDIRECT_MS)
            } catch (e: Exception) {
                EventLog.e("Failed to launch TizenTube", e)
                overlay.hide("launch failed")
            }
            startYouTubeGuard()
            mainHandler.postDelayed({ logForeground() }, FOREGROUND_CHECK_MS)
        }
    }

    /** Records which activity is in front, so the event log shows what the user actually saw. */
    private fun logForeground() {
        Thread({
            val top = try {
                RESUMED_ACTIVITY.matcher(dumpsys("activities")).let { if (it.find()) it.group(1) else "none" }
            } catch (e: Exception) {
                "unknown ($e)"
            }
            EventLog.i("Foreground ${FOREGROUND_CHECK_MS / 1000}s after redirect: $top")
        }, "yt-foreground-check").start()
    }

    /**
     * For [YT_GUARD_MS] after a redirect, stop official YouTube's playback whenever its media
     * session shows up or starts playing. If YouTube got far enough to start the video in the
     * background, the TV's Cast service otherwise pulls YouTube back in front of TizenTube.
     * Needs notification-listener access for [MediaListenerService].
     */
    private fun startYouTubeGuard() {
        guardUntil = SystemClock.uptimeMillis() + YT_GUARD_MS
        mainHandler.removeCallbacks(endGuard)
        mainHandler.postDelayed(endGuard, YT_GUARD_MS)
        try {
            if (!sessionsListenerRegistered) {
                sessionManager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, mainHandler)
                sessionsListenerRegistered = true
            }
            stopYouTubeIn(sessionManager.getActiveSessions(listenerComponent), "YouTube session already active")
        } catch (e: SecurityException) {
            EventLog.w("No notification-listener access; can't stop YouTube playback")
        }
    }

    private val endGuard = Runnable {
        for ((controller, callback) in guardedCallbacks.values) controller.unregisterCallback(callback)
        guardedCallbacks.clear()
    }

    private fun stopYouTubeIn(controllers: List<MediaController>, why: String) {
        if (SystemClock.uptimeMillis() > guardUntil) return
        for (controller in controllers) {
            if (controller.packageName != OFFICIAL_YT) continue
            if (controller.sessionToken !in guardedCallbacks) {
                val callback = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        val s = state?.state
                        if (s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING) {
                            stopYouTube(controller, "YouTube started playing")
                        }
                    }
                }
                controller.registerCallback(callback, mainHandler)
                guardedCallbacks[controller.sessionToken] = controller to callback
            }
            stopYouTube(controller, why)
        }
    }

    private fun stopYouTube(controller: MediaController, why: String) {
        if (SystemClock.uptimeMillis() > guardUntil) return
        val state = controller.playbackState?.state
        if (state == PlaybackState.STATE_STOPPED || state == PlaybackState.STATE_NONE) return
        EventLog.i("Stopping official YouTube playback ($why, state=$state)")
        controller.transportControls.stop()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Detection is logcat-driven; window-state events are only used to lift the launch
        // overlay once TizenTube's window has come to the front after a redirect.
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.packageName == TIZEN_PKG && current?.redirected == true && overlay.isShowing
        ) {
            overlay.revealSoon()
        }
    }

    override fun onInterrupt() {
        // No interruptible feedback to cancel.
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        try {
            logcatProcess?.destroy()
        } catch (_: Exception) {
        }
        logcatThread?.interrupt()
        logcatThread = null
        overlay.hide(null)
        EventLog.i("Service destroyed")
    }
}
