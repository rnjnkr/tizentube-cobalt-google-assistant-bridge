# Setup, internals and lessons learned

Detailed notes behind the [README](README.md). The first part is written as a prompt you can
paste into an AI coding agent (e.g. Claude Code) running in this folder on a computer with
`adb`, the Android SDK and LAN access to the TV; the commands also work by hand (see
"Manual command reference").

---

Context: This project is a Google TV AccessibilityService that redirects Google
Assistant deep-link launches of official YouTube (com.google.android.youtube.tv) to
TizenTube Cobalt (io.gh.reisxd.tizentube.cobalt / dev.cobalt.app.MainActivity). It needs no
root and leaves official YouTube installed, enabled and unmodified: no stand-in app taking
over YouTube's package name, no system changes (see "Don't disable official YouTube" below
for why YouTube must stay). It was
developed on a Sony BRAVIA (platform BRAVIA BF1, MediaTek MT9687) on Android 12 / SDK 31.
The Gradle project is in this folder — service code, manifest with READ_LOGS/DUMP/
PACKAGE_USAGE_STATS, accessibility config, and a Gradle 8.7 wrapper (AGP 8.6.1,
Kotlin 1.9.24, compileSdk/targetSdk 34). applicationId is
io.github.rnjnkr.tizentubebridge. Create local.properties with `sdk.dir=<path to SDK>`.

Never uninstall a system app on the TV — disable it (pm disable-user) instead.

TV: <TV_IP>:5555 (enable Developer options > Network/USB debugging). adb: platform-tools/adb

Please, running locally:
1. Build: ./gradlew assembleDebug  (Gradle 8.7 needs JDK 17–21. Newer Android Studio
   releases bundle Java 25, which fails with just "25.0.3"; point Gradle at a JDK 17–21:
   -Dorg.gradle.java.home=<path to JDK 21>).
   The SDK needs platforms;android-34 and build-tools;34.0.0.
   `./gradlew test` runs the unit tests (LaunchParsing.kt: START lines, dumpsys parsing,
   URLs; PinStore: pins.txt). They need no TV.
2.-4. `./setup-tv.sh <TV_IP>` does steps 2-4 (plus the notification-listener and audio-focus
   steps below) and checks the result. By hand:
2. adb connect to the TV, install -r the debug APK.
3. Grant READ_LOGS, DUMP and PACKAGE_USAGE_STATS via pm grant. All three are needed:
   without PACKAGE_USAGE_STATS, dumpsys activity is refused ("Permission Denial") and
   launches are detected but never redirected. (appops GET_USAGE_STATS alone is not
   enough on this TV.)
4. Enable the accessibility service by APPENDING to enabled_accessibility_services
   (don't overwrite existing), then set accessibility_enabled 1.
5. Verify by streaming: adb logcat -s YTInterceptor, then trigger a YouTube deep link
   ("Hey Google, play X on YouTube") and confirm it redirects to TizenTube.

---

## Prerequisites on the computer
- JDK 17–21 (Java 25, as bundled with newer Android Studio, does not work with Gradle 8.7).
- SDK packages: platforms;android-34 and build-tools;34.0.0.
- TizenTube Cobalt already sideloaded on the TV (the redirect targets its component).

## Manual command reference (what setup-tv.sh runs, if you'd rather do it by hand)
    ./gradlew assembleDebug
    ADB=adb                      # or the full path to platform-tools/adb
    $ADB connect <TV_IP>:5555
    $ADB install -r app/build/outputs/apk/debug/app-debug.apk
    PKG=io.github.rnjnkr.tizentubebridge
    $ADB shell pm grant $PKG android.permission.READ_LOGS
    $ADB shell pm grant $PKG android.permission.DUMP
    $ADB shell pm grant $PKG android.permission.PACKAGE_USAGE_STATS
    COMP=$PKG/$PKG.YouTubeInterceptorService
    CUR=$($ADB shell settings get secure enabled_accessibility_services | tr -d '\r'); [ "$CUR" = "null" ] && CUR=""
    case "$CUR" in *"$COMP"*) ;; *) $ADB shell settings put secure enabled_accessibility_services "${CUR:+$CUR:}$COMP";; esac
    $ADB shell settings put secure accessibility_enabled 1
    # Notification-listener access (only used to stop official YouTube's playback after a
    # redirect; no notifications are read). Adds to the existing listeners, doesn't replace.
    $ADB shell cmd notification allow_listener $PKG/$PKG.MediaListenerService
    # Stop official YouTube from taking audio focus, so it can't pause TizenTube (see
    # "Audio focus" below). Revert with: ... TAKE_AUDIO_FOCUS allow
    $ADB shell appops set com.google.android.youtube.tv TAKE_AUDIO_FOCUS ignore
    $ADB logcat -s YTInterceptor

`install -r` keeps the grants and the accessibility setting, so after code changes only
the build + install steps need re-running. Everything survives a reboot / power cut.
The YouTube app-op survives reboots too, but a factory reset or a YouTube reinstall /
"Uninstall updates" may reset it; re-check with
`$ADB shell appops get com.google.android.youtube.tv TAKE_AUDIO_FOCUS`.

## Launch overlay
From the moment a YouTube deep link is detected until TizenTube's window is in front, the
service shows a full-screen card in TizenTube's colours (its icon, read from the installed
app at runtime, the spoken title, "Opening in TizenTube" and a progress bar), so the brief moment official
YouTube is on screen is never seen. It is an accessibility overlay (no extra permission),
never takes focus (remote keys pass through), and is removed when TizenTube's window-state
event arrives (+250 ms to let it draw), when a redirect is abandoned, 2.5 s after the
redirect at the latest, and never stays more than 5 s. YouTube opened from the launcher
(no URL) doesn't show it. Code: `LaunchOverlay.kt`.

## Audio focus
If a command arrives while TizenTube is already playing, TizenTube reloads fast and starts
playing before official YouTube (warm, now in the background) finishes loading the
Assistant's pick. YouTube then requests audio focus (~2.5 s after the redirect), and
TizenTube, on that permanent focus loss, pauses for good; the media-session guard stops
YouTube but can't give the focus back. Denying YouTube's `TAKE_AUDIO_FOCUS` app-op (above)
makes that request fail, so TizenTube never loses focus. Killing YouTube isn't an option:
FORCE_STOP_PACKAGES is signature|privileged, and killBackgroundProcesses is refused / too late.

Audio focus is only coordination, not permission to make sound (that is the separate
`PLAY_AUDIO` op, left alone), so official YouTube still plays normally when opened directly.
The one side effect: it no longer asks other apps to go quiet, so a background music app
(Spotify, YouTube Music) keeps playing over it until paused by hand. YouTube, TizenTube and
Netflix suspend themselves when they leave the screen, so they're unaffected.

## Event log (redirect history)
The TV's logcat `main` buffer only holds ~15 minutes (the display driver's `PqLink` tag
floods it), so `adb logcat -s YTInterceptor` often shows nothing for past commands. The
service also writes every event to a rolling file next to pins.txt (the app's external files
dir; it used to be private storage, which `run-as` can only read on debug builds):

    $ADB shell cat /sdcard/Android/data/io.github.rnjnkr.tizentubebridge/files/events.log

Lines are `epoch-ms <TAB> date time <TAB> level <TAB> message`. Entries older than 24 h are
dropped on each write, capped at 1000 lines / 500 chars per message (< ~0.5 MB). A
reinstall shows a new "Service connected" line with no matching "destroyed" line; that's
expected (the process is killed without onDestroy). On Android 11+ other apps can't read this
folder; on Android 10 and older, apps with storage permission could (it holds voice queries
and video links).

## Pinned voice queries
`pins.txt` maps what the Assistant heard (the URL's `vq` parameter) to a fixed URL, because
the Assistant's pick for a phrase changes from day to day. One `phrase: url` per line (split
at the first colon; `#` comments and blank lines skipped). Matching is exact but
case-insensitive, ignoring a leading "play" and trailing "on YouTube"; URLs must be
youtube.com / youtu.be.

The master copy is `pins.txt` in this folder, or `pins.local.txt` (git-ignored, preferred by
the script when present) for pins you don't want to commit. Edit it, then run `./push-pins.sh <TV_IP>`
(or set `TV_IP` / `ADB` in the environment), which copies it to `/sdcard/Android/data/io.github.rnjnkr.tizentubebridge/files/pins.txt`. No rebuild
needed: the service re-reads the file when its timestamp or size changes (checked on each
command) and logs `Loaded N pin(s)`, plus an `ignored` line for any malformed entry. If the
file is missing on the TV, the service recreates it from `PinStore.kt` (commented examples, no
active pins).

## Lessons learned (Oct 2026)
- **Log buffer:** ActivityTaskManager START lines go to the `system` buffer, not `main`.
  The URL in them is redacted (`dat=http://www.youtube.com/...`), hence dumpsys.
- **Stale URLs:** YouTube's task keeps its original root intent forever, so "first
  YouTube intent in dumpsys" can be an old video. The service reads the last start from
  `dumpsys activity starter` and refuses to guess if it can't match the launch.
- **Trampoline:** YouTube launches via ShellActivity -> MainActivity. On a cold start
  (after boot, or after YouTube was reclaimed) that hop takes ~1 s; the service waits for
  it so TizenTube lands on top. First command after a power-on takes a few seconds
  (both apps cold-starting), and a moment of YouTube audio may be heard.
- **Clearing YouTube from RAM is not possible** from the app: killBackgroundProcesses is
  refused for it (system app). It's left as a cached process (~76 MB) that Android
  reclaims as needed.
- **Don't disable official YouTube:** the Assistant then says "YouTube isn't installed"
  and opens the Play Store — it never launches anything the service could intercept.
- **YouTube can win the race** (dumpsys can take 200–700 ms on a busy TV). If YouTube
  starts playing in the background, the TV's Cast service (mediashell / "Warg") pulls YouTube
  back in front ~5 s later. The service therefore runs dumpsys off the logcat thread and, for
  15 s after each redirect, stops official YouTube's media session (needs the
  notification-listener grant above). Verified with a forced 4 s delay: YouTube was playing,
  got stopped, and Cast handed over to TizenTube.
- **Don't use `logcat -c`** in the service: it wipes the whole device log. The watcher
  uses `logcat -T <now>` instead.

## Lessons learned (Nov 2026)
- **First command after a cold boot can land on the home screen.** When the Assistant
  wakes the screen, the Google TV launcher's "background timeout" reset (logged by
  `BootModeAppToForeground`) can fire ~300 ms after YouTube was launched and bury it; the
  starter record then shows the launcher's intent, so the service (correctly) won't guess a
  URL. Repeating the command works. Possible fix, not implemented: recover the URL from
  `dumpsys activity recents`, which keeps each task's full launch intent. Meanwhile, an
  Assistant launch whose link can't be read opens TizenTube's home screen instead.
- **YouTube can steal audio focus from an already-playing TizenTube**: fixed with the
  `TAKE_AUDIO_FOCUS` app-op (see "Audio focus").
- **Search commands carry JSON in the URL.** "Search for Minecraft videos" launches YouTube
  with `https://www.youtube.com/tv?voice={"youtubeAssistantRequest":{"query":"search for
  Minecraft videos",…}}&launch=voice&vq=…` (unencoded braces and spaces). The old URL regex
  stopped at the first `}`, so searches were never redirected. The URL is now read up to the
  next Intent field (` flg=`, ` pkg=`, ` cmp=`…). TizenTube understands the `voice=` request
  natively and opens its search results.
- **"Open YouTube" also opens TizenTube.** The Assistant sends it as a link too, a bare
  home-page one (`https://www.youtube.com/?command_id=…&launch=voice&vs=1`), so it is
  redirected like any other (TizenTube opens on its home screen). Only launches without a link
  (YouTube opened from the launcher with the remote) are left alone. An Assistant
  launch that did carry a link (recognised by com.google.android.katniss's uid
  in the START line) whose URL can't be read opens TizenTube's home screen rather than
  leaving official YouTube up.
- **Package renamed** from `com.example.youtubetvinterceptor` to
  `io.github.rnjnkr.tizentubebridge`. Android treats it as a different app: remove the old
  one (`adb uninstall com.example.youtubetvinterceptor`) or both will redirect, and push
  pins.txt again (its path on the TV includes the package name). setup-tv.sh warns if the
  old package is still installed.
