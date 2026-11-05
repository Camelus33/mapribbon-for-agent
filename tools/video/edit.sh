#!/bin/bash
# Assembles out/video/maptalk-drive-demo.mp4 (run make_cards.py first; raw clips come from rec.sh/pull.sh) (1920x1080, 30 fps) from the raw emulator recordings and caption PNGs.
set -euo pipefail
cd "$(dirname "$0")/../../out/video"
FF="/opt/homebrew/bin/ffmpeg -hide_banner -loglevel error -y"
ENC="-c:v libx264 -preset slow -crf 18 -pix_fmt yuv420p -r 30 -an"
APP="crop=1706:960:854:90,scale=1920:1080:flags=lanczos,fps=30"   # the app's window inside the Pleos screen
mkdir -p seg

# Still cards.
$FF -loop 1 -t 5 -i cap/title.png -vf "fps=30,fade=t=in:st=0:d=0.6,fade=t=out:st=4.4:d=0.6" $ENC seg/00.mp4
$FF -loop 1 -t 7 -i cap/closing.png -vf "fps=30,fade=t=in:st=0:d=0.6,fade=t=out:st=6.2:d=0.8" $ENC seg/90.mp4

# One caption over a cropped app scene.
one() { # <raw> <caption> <out> <crop/scale filter>
  $FF -i "raw/$1.mp4" -i "cap/$2.png" -filter_complex "[0:v]$4[v];[v][1:v]overlay=0:0" $ENC "seg/$3.mp4"
}
# Two or three captions switching at given output times.
two() { # <raw> <capA> <capB> <switch_s> <out>
  $FF -i "raw/$1.mp4" -i "cap/$2.png" -i "cap/$3.png" -filter_complex \
    "[0:v]$APP[v];[v][1:v]overlay=0:0:enable='lt(t,$4)'[a];[a][2:v]overlay=0:0:enable='gte(t,$4)'" $ENC "seg/$5.mp4"
}

one s2_empty c_empty 10 "$APP"
two s3_fill c_fill1 c_fill2 7 20
two s4_control c_ctrl1 c_ctrl2 6.5 30
# Drive: the 20x simulation from the first driving frame, played 2.5x faster again (about 27 s on screen).
$FF -i raw/s5_drive.mp4 -i cap/c_drive1.png -i cap/c_drive2.png -i cap/c_drive3.png -filter_complex \
  "[0:v]trim=3:70,setpts=(PTS-STARTPTS)/2.5,$APP[v];[v][1:v]overlay=0:0:enable='lt(t,8)'[a];[a][2:v]overlay=0:0:enable='between(t,8,19.5)'[b];[b][3:v]overlay=0:0:enable='gt(t,19.5)'" \
  $ENC seg/40.mp4
# Signals: zoom on the console's vehicle panel.
one s6_signals c_signals 50 "crop=960:540:1600:880,scale=1920:1080:flags=lanczos,fps=30"
# Restriction: the whole Pleos screen, so the cluster's gear shows.
one s7_restrict c_restrict 60 "scale=1920:1080:flags=lanczos,fps=30"

(cd seg && ls *.mp4 | sed 's/^/file /' > list.txt)
$FF -f concat -safe 0 -i seg/list.txt -c copy maptalk-drive-demo.mp4
/opt/homebrew/bin/ffprobe -v error -show_entries format=duration:stream=width,height -of default=nw=1 maptalk-drive-demo.mp4
