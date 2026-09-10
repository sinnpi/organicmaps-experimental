#!/usr/bin/env bash
#
# Samples the phone's battery fuel gauge at ~1 Hz and writes a CSV.
#
# An inline USB power meter measures charging current, which is dominated by the charger's own
# behaviour and masks system draw, so the device must run on battery: connect adb over WiFi
# (adb tcpip 5555 && adb connect <ip>:5555) and physically unplug USB before measuring.
#
# Usage: sample_power.sh <label> [duration_seconds] [out_dir]
#
# The whole sampling loop runs inside a single persistent adb shell: spawning one adb process per
# sample would itself cost more power than several of the effects we are trying to measure.

set -euo pipefail

LABEL="${1:?usage: sample_power.sh <label> [duration_seconds] [out_dir]}"
DURATION="${2:-600}"
OUT_DIR="${3:-power-runs}"

SERIAL_ARGS=()
[[ -n "${ANDROID_SERIAL:-}" ]] && SERIAL_ARGS=(-s "$ANDROID_SERIAL")

# Candidate sysfs nodes, most specific first. Vendors disagree; probe on the device.
PSY_CANDIDATES="battery bms max170xx_battery"

probe_supply() {
  adb "${SERIAL_ARGS[@]}" shell '
    for p in '"$PSY_CANDIDATES"'; do
      d=/sys/class/power_supply/$p
      if [ -r "$d/current_now" ] && [ -r "$d/voltage_now" ]; then echo "$d"; exit 0; fi
    done
    exit 1
  ' | tr -d '\r'
}

SUPPLY="$(probe_supply)" || {
  echo "ERROR: no readable power_supply node with current_now + voltage_now." >&2
  echo "Inspect manually: adb shell ls /sys/class/power_supply/" >&2
  exit 1
}

CHARGING="$(adb "${SERIAL_ARGS[@]}" shell "cat $SUPPLY/status" | tr -d '\r')"
if [[ "$CHARGING" == "Charging" || "$CHARGING" == "Full" ]]; then
  echo "ERROR: battery status is '$CHARGING'. Unplug USB and use adb over WiFi;" >&2
  echo "       readings taken while charging measure the charger, not the system." >&2
  exit 1
fi

mkdir -p "$OUT_DIR"
OUT="$OUT_DIR/${LABEL}_$(date +%Y%m%d-%H%M%S).csv"

echo "supply=$SUPPLY label=$LABEL duration=${DURATION}s -> $OUT"
echo "# supply=$SUPPLY label=$LABEL duration=$DURATION" > "$OUT"
echo "timestamp,label,current_raw,voltage_raw,temp_decic,capacity" >> "$OUT"

# One shell, one loop. `date +%s` is recorded per sample so that sleep drift is visible in the data
# rather than silently assumed away.
adb "${SERIAL_ARGS[@]}" shell "
  end=\$(( \$(date +%s) + $DURATION ))
  while [ \$(date +%s) -lt \$end ]; do
    printf '%s,%s,%s,%s,%s\n' \
      \"\$(date +%s)\" \
      \"\$(cat $SUPPLY/current_now 2>/dev/null || echo NA)\" \
      \"\$(cat $SUPPLY/voltage_now 2>/dev/null || echo NA)\" \
      \"\$(cat $SUPPLY/temp 2>/dev/null || echo NA)\" \
      \"\$(cat $SUPPLY/capacity 2>/dev/null || echo NA)\"
    sleep 1
  done
" | tr -d '\r' | while IFS= read -r line; do
  ts="${line%%,*}"
  rest="${line#*,}"
  echo "$ts,$LABEL,$rest" >> "$OUT"
done

echo "wrote $(( $(wc -l < "$OUT") - 2 )) samples to $OUT"
