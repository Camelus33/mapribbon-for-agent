#!/bin/bash
# pull.sh <name>: waits for the recording to finish and pulls it.
export PATH=$HOME/Library/Android/sdk/platform-tools:$PATH
while adb shell pidof screenrecord >/dev/null 2>&1; do sleep 1; done
adb pull /sdcard/$1.mp4 "$(dirname "$0")/../../out/video/raw"/$1.mp4 >/dev/null && ls -la "$(dirname "$0")/../../out/video/raw"/$1.mp4 | awk '{print $5, $9}'
