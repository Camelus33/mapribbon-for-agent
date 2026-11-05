#!/bin/bash
# tap.sh "<text substring>" : taps the centre of the first UI node whose text contains the substring.
export PATH=$HOME/Library/Android/sdk/platform-tools:$PATH
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb shell cat /sdcard/ui.xml | /usr/bin/python3 -c '
import sys,re
xml=sys.stdin.read(); want=sys.argv[1]
for m in re.finditer(r"<node [^>]*>", xml):
    n=m.group(0); t=re.search(r"text=\"([^\"]*)\"",n); b=re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",n)
    if t and want in t.group(1) and b:
        x1,y1,x2,y2=map(int,b.groups()); print((x1+x2)//2,(y1+y2)//2); break
' "$1" | { read x y; if [ -n "$x" ]; then adb shell input tap $x $y; echo "tapped '$1' at $x,$y"; else echo "not found: $1"; fi; }
