#!/bin/sh
# Installs the app on the TV and grants everything it needs (see README). Safe to re-run,
# e.g. after a rebuild: grants and settings already in place are left as they are.
#
# Usage: ./setup-tv.sh <tv-ip> [apk]   (or set TV_IP; set ADB if adb isn't on your PATH)
#   apk defaults to the debug build: app/build/outputs/apk/debug/app-debug.apk
#   SKIP_AUDIO_FOCUS=1 leaves official YouTube's TAKE_AUDIO_FOCUS app-op alone.
set -e
ADB=${ADB:-adb}
TV_IP=${1:-$TV_IP}
if [ -z "$TV_IP" ]; then
    echo "Usage: $0 <tv-ip> [apk]   (or set TV_IP)" >&2
    exit 1
fi
TV=$TV_IP:5555
PKG=io.github.rnjnkr.tizentubebridge
OLD_PKG=com.example.youtubetvinterceptor
YT=com.google.android.youtube.tv
TIZEN=io.gh.reisxd.tizentube.cobalt

cd "$(dirname "$0")"
APK=${2:-app/build/outputs/apk/debug/app-debug.apk}
if [ ! -f "$APK" ]; then
    echo "APK not found: $APK (build it first: ./gradlew assembleDebug)" >&2
    exit 1
fi

# tv: read a value from the TV. run: change something; a failure stops the script.
tv() { $ADB -s $TV shell "$@" | tr -d '\r'; }
run() { $ADB -s $TV shell "$@"; }

$ADB connect $TV >/dev/null
if ! $ADB -s $TV get-state >/dev/null 2>&1; then
    echo "Can't reach $TV. Is the TV on, with network debugging enabled (and this computer allowed)?" >&2
    exit 1
fi

tv pm list packages $TIZEN | grep -q "package:$TIZEN\$" ||
    echo "Warning: TizenTube Cobalt ($TIZEN) isn't installed; there is nothing to redirect to yet."

echo "Installing $APK"
$ADB -s $TV install -r "$APK"

echo "Granting permissions"
for p in READ_LOGS DUMP PACKAGE_USAGE_STATS; do
    run pm grant $PKG android.permission.$p
done

echo "Enabling the accessibility service"
COMP=$PKG/$PKG.YouTubeInterceptorService
CUR=$(tv settings get secure enabled_accessibility_services)
[ "$CUR" = "null" ] && CUR=""
case "$CUR" in
    *"$COMP"*) ;;
    *) run settings put secure enabled_accessibility_services "${CUR:+$CUR:}$COMP" ;;
esac
run settings put secure accessibility_enabled 1

echo "Allowing media-session access (to stop official YouTube's playback after a redirect)"
run cmd notification allow_listener $PKG/$PKG.MediaListenerService

if [ -z "$SKIP_AUDIO_FOCUS" ]; then
    echo "Stopping official YouTube from taking audio focus (undo: adb shell appops set $YT TAKE_AUDIO_FOCUS allow)"
    run appops set $YT TAKE_AUDIO_FOCUS ignore
fi

if tv pm list packages $OLD_PKG | grep -q "package:$OLD_PKG\$"; then
    echo
    echo "Note: an older build ($OLD_PKG) is also installed and would redirect too."
    echo "Remove it with: $ADB -s $TV uninstall $OLD_PKG"
fi

echo
echo "Checking:"
GRANTED=$(tv dumpsys package $PKG | grep -cE "android.permission.(READ_LOGS|DUMP|PACKAGE_USAGE_STATS): granted=true" || true)
echo "  permissions granted: $GRANTED of 3"
case "$(tv settings get secure enabled_accessibility_services)" in
    *"$COMP"*) echo "  accessibility service: enabled" ;;
    *) echo "  accessibility service: NOT enabled" ;;
esac
sleep 2
LOG=/sdcard/Android/data/$PKG/files/events.log
if tv cat $LOG 2>/dev/null | grep -q "Service connected"; then
    echo "  service: running"
else
    echo "  service: not started yet (check again with: $ADB -s $TV shell cat $LOG)"
fi
echo
echo 'Done. Try "Hey Google, play lofi music on YouTube".'
