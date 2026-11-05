#!/bin/sh
# Copies pins.txt from this folder to the TV (or pins.local.txt if it exists: a git-ignored
# copy for your own pins). The interceptor picks up the change on the next voice command;
# check the event log for "Loaded N pin(s)" or "ignored" lines.
#
# Usage: ./push-pins.sh <tv-ip>       (or set TV_IP; set ADB if adb isn't on your PATH)
set -e
ADB=${ADB:-adb}
TV_IP=${1:-$TV_IP}
if [ -z "$TV_IP" ]; then
    echo "Usage: $0 <tv-ip>   (or set TV_IP)" >&2
    exit 1
fi
TV=$TV_IP:5555
DEST=/sdcard/Android/data/io.github.rnjnkr.tizentubebridge/files/pins.txt

cd "$(dirname "$0")"
SRC=pins.txt
[ -f pins.local.txt ] && SRC=pins.local.txt
echo "Pushing $SRC"
$ADB connect $TV >/dev/null
$ADB -s $TV push $SRC $DEST
echo "Pushed. Recent pin log lines:"
$ADB -s $TV shell run-as io.github.rnjnkr.tizentubebridge cat files/events.log | grep -E "pins\.txt|pin\(s\)" | tail -3 | cut -f2-
