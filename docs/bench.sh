#!/bin/zsh
# Benchmarks the stock Amazon launcher against Fast Home on a connected Fire TV; prints Markdown table rows.
# Needs adb (connected to $DEV) and ffmpeg. Turns the Fast Home helper off for the stock runs, then back on.
# Usage: DEV=192.168.1.136:5555 N=5 docs/bench.sh
DEV=${DEV:-192.168.1.136:5555}; N=${N:-5}
STOCK=com.amazon.tv.launcher/.ui.HomeActivity_vNext FAST=fast.home/.Home
NEUTRAL=com.amazon.tv.settings.v2/.tv.network.NetworkActivity HELPER=fast.home.redirect/fast.home.HomeRedirect
TMP=$(mktemp -d)
a() { adb -s $DEV shell "$@"; }
median() { sort -n | awk '{v[NR]=$1} END {print (NR % 2) ? v[(NR + 1) / 2] : int((v[NR / 2] + v[NR / 2 + 1]) / 2)}'; }
neutral() { a am start -n $NEUTRAL >/dev/null; sleep 3; }
start() { a am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n $1 | awk '/TotalTime/ {print $2}'; }
helper() { a settings put secure enabled_accessibility_services ${1:-'""'}; [[ -n $1 ]] && a settings put secure accessibility_enabled 1; sleep 2; }
pss() { a dumpsys meminfo $1 | awk '/TOTAL PSS:/ {print int($3 / 1024)}'; }                       # MB
avail() { a cat /proc/meminfo | awk '/MemAvailable/ {print int($2 / 1024)}'; }                       # MB
ticks() { a cat /proc/$(a pidof $1)/stat | awk '{print $14 + $15}'; }                              # CPU time, 1/100 s
busy() { a cat /proc/stat | awk '/^cpu / {print $2 + $3 + $4 + $7 + $8, $5 + $6}'; }                # busy, idle jiffies

# Home press -> screen settled (ms): from the first big on-screen change to the last real change before 1 s of quiet.
home_press() {
  neutral
  a "screenrecord --time-limit 6 --bit-rate 12000000 /sdcard/bench.mp4" & sleep 2.5
  a input keyevent KEYCODE_HOME; wait
  adb -s $DEV pull -q /sdcard/bench.mp4 $TMP/h.mp4
  ffmpeg -v info -i $TMP/h.mp4 -vf "scale=320:180,format=gray,select='gte(scene,0)',metadata=print:key=lavfi.scene_score" -f null - 2>&1 \
    | grep -oE 'pts_time:[0-9.]+|scene_score=[0-9.]+' | sed -E 's/pts_time:|scene_score=//' | paste -d' ' - - \
    | awk '{ if (!f) { if ($2 > 0.1) { f = $1; l = $1 } next } if ($1 - l > 1.0) exit; if ($2 > 0.005) l = $1 } END { print int((l - f) * 1000) }'
}

# Scroll smoothness: the same 20 D-pad presses on each home screen, measured by the app's own frame stats.
scroll() {
  local pkg=${1%%/*}; a dumpsys gfxinfo $pkg reset >/dev/null
  a 'for k in 22 22 22 22 22 22 22 22 20 20 21 21 21 21 21 21 21 21 19 19; do input keyevent $k; done'
  sleep 1; a dumpsys gfxinfo $pkg | awk '/Total frames rendered/ {t=$4} /Janky frames:/ {j=$4} /50th percentile/ {p50=$3} /90th percentile/ {p90=$3} /99th percentile/ {p99=$3}
    END {printf "%s frames, %s janky, p50 %s, p90 %s, p99 %s\n", t, j, p50, p90, p99}'
}

# Idle cost over 30 s on the home screen: the launcher's own CPU, plus how busy the whole TV is.
idle() {
  local t0=$(ticks ${1%%/*}) s0=($(busy)); sleep 30; local t1=$(ticks ${1%%/*}) s1=($(busy))
  awk -v t=$((t1 - t0)) -v b=$((s1[1] - s0[1])) -v i=$((s1[2] - s0[2])) 'BEGIN {printf "%.1f%% of one core; whole TV %.0f%% busy\n", t / 30, 100 * b / (b + i)}'
}

run() {  # run <name> <component>
  local name=$1 comp=$2 pkg=${2%%/*}
  echo "## $name"
  echo "hot start (ms, median of $N): $(for i in {1..$N}; do neutral; start $comp; done | median)"
  echo "home press -> settled (ms, median of $N): $(for i in {1..$N}; do home_press; done | median)"
  a input keyevent KEYCODE_HOME; sleep 20
  echo "memory: launcher $(pss $pkg) MB PSS, Amazon launcher $(pss com.amazon.tv.launcher) MB, TV MemAvailable $(avail) MB"
  echo "idle 30 s: $(idle $comp)"
  echo "scroll: $(scroll $comp)"
  echo "APK: $(a stat -c %s $(a pm path $pkg | head -1 | cut -d: -f2) | awk '{printf "%.1f MB", $1 / 1048576}')"
}

helper; run "Stock Amazon launcher" $STOCK
helper $HELPER; run "Fast Home (with Home-button helper)" $FAST
echo "## Fast Home cold start (ms, median of $N): $(for i in {1..$N}; do neutral; a am force-stop fast.home; sleep 1; start $FAST; done | median)"
a input keyevent KEYCODE_HOME
rm -rf $TMP
