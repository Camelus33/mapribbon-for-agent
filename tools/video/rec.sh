#!/bin/bash
# rec.sh <name> <seconds>: starts a screen recording on the emulator in the background.
export PATH=$HOME/Library/Android/sdk/platform-tools:$PATH
adb shell rm -f /sdcard/$1.mp4
adb shell screenrecord --time-limit $2 --bit-rate 12000000 /sdcard/$1.mp4 >/dev/null 2>&1 &
sleep 1
