#!/usr/bin/env bash
#
# Runs one labelled condition of the power attribution matrix: pins device state, waits for the
# operator to put the app into the condition, then samples unattended for the duration.
#
# Conditions are set up by hand (typically by typing a debug command such as `?ulp-fps=10` into the
# app's search box). The app exposes no intent carrying a search query, and UI automation keyed to
# tap coordinates breaks on every layout change -- for ~30 runs of a few seconds' setup each, the
# operator is the more reliable component.
#
# Usage: run_condition.sh <label> [duration_seconds] [--brightness N] [--no-wait]
#
# See docs/POWER_MEASUREMENT.md for the condition list and the protocol.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"

LABEL="${1:?usage: run_condition.sh <label> [duration_seconds] [--brightness N] [--no-wait]}"
shift
DURATION=600
BRIGHTNESS=128
WAIT=1

while (( $# )); do
  case "$1" in
    --brightness) BRIGHTNESS="$2"; shift 2 ;;
    --no-wait)    WAIT=0; shift ;;
    *[!0-9]*)     echo "unknown argument: $1" >&2; exit 2 ;;
    *)            DURATION="$1"; shift ;;
  esac
done

"$HERE/prepare_device.sh" "$BRIGHTNESS"

cat <<TXT

== condition: $LABEL ==
Put the device into the condition now. Reminders:
  - the same route, the same map region and the same zoom as every other run
  - start route simulation (or real navigation) BEFORE starting the sample
  - the first ${WARMUP:-60}s are discarded, so small setup jitter is harmless
TXT

if (( WAIT )); then
  read -r -p "Press Enter to start sampling for ${DURATION}s (Ctrl-C to abort)... "
fi

"$HERE/sample_power.sh" "$LABEL" "$DURATION" "${OUT_DIR:-power-runs}"

echo
echo "Summarise once you have several conditions:"
echo "  tools/power/summarize.py --baseline C4 power-runs/*.csv"
