#!/usr/bin/env bash
#
# Pins the device state that power measurements are sensitive to, and reports anything it could
# not set so it can be done by hand. Run this before every condition, not just once per session:
# the system silently restores some of these (notably adaptive brightness) after reboots and
# battery-saver transitions.
#
# Usage: prepare_device.sh [brightness_0_255]

set -euo pipefail

BRIGHTNESS="${1:-128}"
SERIAL_ARGS=()
[[ -n "${ANDROID_SERIAL:-}" ]] && SERIAL_ARGS=(-s "$ANDROID_SERIAL")

sh() { adb "${SERIAL_ARGS[@]}" shell "$@" 2>/dev/null | tr -d '\r'; }

manual=()

set_checked() { # namespace key value description
  sh "settings put $1 $2 $3" || true
  local got
  got="$(sh "settings get $1 $2")"
  if [[ "$got" != "$3" ]]; then
    manual+=("$4 (wanted $3, got '$got')")
  fi
}

echo "== pinning device state =="
set_checked system screen_brightness_mode 0 "disable adaptive brightness"
set_checked system screen_brightness "$BRIGHTNESS" "set brightness to $BRIGHTNESS/255"
set_checked system screen_off_timeout 1800000 "set screen timeout to 30 min"

# Radios. `svc` needs privileges the shell user usually lacks on modern Android, so treat failure
# as "tell the human" rather than pretending it worked.
for probe in "svc data disable:mobile data off" "svc bluetooth disable:bluetooth off"; do
  cmd="${probe%%:*}"; desc="${probe##*:}"
  out="$(sh "$cmd" || true)"
  [[ -n "$out" ]] && manual+=("$desc — '$cmd' said: $out")
done

echo
echo "== current state =="
printf 'brightness      : %s (mode %s)\n' "$(sh 'settings get system screen_brightness')" \
                                          "$(sh 'settings get system screen_brightness_mode')"
printf 'battery         : %s%% %s %s dC\n' \
  "$(sh 'cat /sys/class/power_supply/battery/capacity')" \
  "$(sh 'cat /sys/class/power_supply/battery/status')" \
  "$(sh 'cat /sys/class/power_supply/battery/temp')"
printf 'panel refresh   : %s\n' "$(sh 'dumpsys display | grep -m1 -iE "fps|refreshRate"' || echo unknown)"
printf 'battery saver   : %s\n' "$(sh 'settings get global low_power')"

if (( ${#manual[@]} )); then
  echo
  echo "== set these by hand before measuring =="
  printf '  - %s\n' "${manual[@]}"
fi

echo
echo "Reminder: unplug USB and use adb over WiFi, and let the phone cool to a stable"
echo "temperature between runs — panel and SoC power both drift with temperature."
