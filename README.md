# TizenTube Cobalt Google Assistant Bridge (no root)

Say *"Hey Google, play … on YouTube"* on an Android / Google TV and have it play in
[TizenTube Cobalt](https://github.com/reisxd/TizenTubeCobalt) instead of the official YouTube app.

Google Assistant can only open the official YouTube app. This small accessibility service
notices the Assistant's YouTube launch, reads the video or playlist link it asked for, and
re-opens that link in TizenTube, without root, hiding the hand-over behind a TizenTube-styled
"Opening in TizenTube" card.

## Features

- **Voice commands open TizenTube.** Videos, playlists, mixes, the Assistant's picks for
  "play \<song/artist\> on YouTube", and searches ("search for Minecraft videos" lands on
  TizenTube's search results). "Open YouTube" opens TizenTube too.
- **No YouTube flash.** A full-screen card in TizenTube's colours (with the spoken title)
  covers the switch and fades out once TizenTube is in front.
- **No interference.** Official YouTube's background playback is stopped for 15 s after each
  redirect, so the TV's Cast service can't pull it back on top, and (with one `appops`
  command) it can't steal audio focus and pause TizenTube.
- **Pinned phrases.** A `pins.txt` file maps phrases to fixed URLs
  (`lofi hip hop: https://www.youtube.com/watch?v=…`), because the Assistant's pick for a
  phrase changes from day to day. Edit and push it; no rebuild needed.
- **Event log.** A rolling 24-hour log of every detection and redirect on the TV.
- **Opening YouTube yourself still works.** Opening it from the launcher with the remote (no
  link) is left alone.

## How it works

1. The service tails `logcat` for ActivityTaskManager `START` lines that target
   `com.google.android.youtube.tv`.
2. Those lines redact the URL, so it reads the full intent from `dumpsys activity starter`,
   and refuses to guess if it can't match the launch, so you never get a stale video.
3. It waits for YouTube's `ShellActivity → MainActivity` hop, then starts TizenTube's
   `dev.cobalt.app.MainActivity` with `ACTION_VIEW` and the same URL, so TizenTube lands on top.
4. Being an accessibility service is what keeps it running from boot and allows starting
   TizenTube from the background; it also draws the cover card as an accessibility overlay.

Details, timings and the many dead ends are in [KICKOFF.md](KICKOFF.md).

## Requirements

- Android TV / Google TV with Google Assistant and the official YouTube app, which must
  **stay enabled** (if disabled, the Assistant sends you to the Play Store instead).
- [TizenTube Cobalt](https://github.com/reisxd/TizenTubeCobalt) installed.
- A computer with `adb` and the TV's network debugging (or USB debugging) enabled.
  The required permissions can only be granted over adb; no root is needed.
- To build: JDK 17–21 and Android SDK platform 34 / build-tools 34.0.0.

Developed and tested on a Sony BRAVIA (Google TV, Android 12). Other Android 10+ TVs should
work, but the log formats it relies on aren't guaranteed to be identical everywhere.

## Install

```sh
./gradlew assembleDebug
adb connect <TV_IP>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk

PKG=com.example.youtubetvinterceptor
adb shell pm grant $PKG android.permission.READ_LOGS
adb shell pm grant $PKG android.permission.DUMP
adb shell pm grant $PKG android.permission.PACKAGE_USAGE_STATS

# Enable the accessibility service (appends to any already enabled)
COMP=$PKG/$PKG.YouTubeInterceptorService
CUR=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r'); [ "$CUR" = "null" ] && CUR=""
case "$CUR" in *"$COMP"*) ;; *) adb shell settings put secure enabled_accessibility_services "${CUR:+$CUR:}$COMP";; esac
adb shell settings put secure accessibility_enabled 1

# Media-session access, used only to stop official YouTube's playback after a redirect
adb shell cmd notification allow_listener $PKG/$PKG.MediaListenerService

# Optional but recommended: stop official YouTube from pausing TizenTube via audio focus.
# Undo with: ... TAKE_AUDIO_FOCUS allow
adb shell appops set com.google.android.youtube.tv TAKE_AUDIO_FOCUS ignore
```

Grants and settings survive reboots and `install -r` updates. Then say *"Hey Google, play
lofi music on YouTube"*.

## Pinned phrases

Edit [`pins.txt`](pins.txt), one `phrase: url` per line:

```
# leading "play" and trailing "on YouTube" are ignored; case-insensitive
lofi hip hop: https://www.youtube.com/watch?v=jfKfPfyJRdk
```

and push it with `./push-pins.sh <TV_IP>`. The service re-reads it on the next command.
To keep your own pins out of git, put them in `pins.local.txt` (git-ignored) instead; the
script pushes that file when it exists.

## Logs

```sh
adb shell run-as com.example.youtubetvinterceptor cat files/events.log
```

## Known limitations

- **First command after a cold boot** can occasionally land on the home screen: the Google TV
  launcher's wake-up reset can bury YouTube before the link is read; TizenTube then opens on its
  home screen instead of the video. Repeating the command works.
- In-app voice features of official YouTube (e.g. "like this video") don't apply to TizenTube;
  standard media commands (pause, next, rewind…) reach TizenTube through its media session.
- With the `TAKE_AUDIO_FOCUS` app-op denied, official YouTube (when opened by hand) won't pause
  a background music app such as Spotify.

## Disclaimer

Not affiliated with or endorsed by Google, YouTube, Sony or the TizenTube project. "Google
Assistant" and "YouTube" are trademarks of Google LLC. Use at your own risk.

## License

[GPL-3.0](LICENSE)
