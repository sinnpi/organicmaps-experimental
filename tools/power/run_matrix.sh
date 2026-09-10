#!/usr/bin/env bash
#
# Walks the power attribution matrix, prompting for each condition and summarising at the end.
#
# The full matrix at measurement-grade settings is 13 conditions x 3 repeats x 10 min, which is over
# six hours of sitting with a phone. So the default is a triage pass -- one 5 minute run per
# condition -- which is enough to see which levers are worth anything. Re-run the survivors with
# --full for numbers you would quote.
#
# Usage:
#   run_matrix.sh                      # triage: every condition once, 5 min each
#   run_matrix.sh --full               # 3 repeats, 10 min each
#   run_matrix.sh C4 C7-10 C13         # only these conditions
#   run_matrix.sh --duration 600 --repeats 2 C4 C13
#
# See docs/POWER_MEASUREMENT.md for what each condition isolates.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
export OUT_DIR="${OUT_DIR:-power-runs}"

DURATION=300
REPEATS=1
BRIGHTNESS="${BRIGHTNESS:-128}"
SELECTED=()

while (( $# )); do
  case "$1" in
    --full)      DURATION=600; REPEATS=3; shift ;;
    --duration)  DURATION="$2"; shift 2 ;;
    --repeats)   REPEATS="$2"; shift 2 ;;
    --brightness) BRIGHTNESS="$2"; shift 2 ;;
    -h|--help)   sed -n '2,20p' "$0"; exit 0 ;;
    -*)          echo "unknown option: $1" >&2; exit 2 ;;
    *)           SELECTED+=("$1"); shift ;;
  esac
done

# label|what to do on the device
CONDITIONS=(
  "C0|Screen off, app force-stopped"
  "C1|Screen on showing a full-screen BLACK image, app force-stopped"
  "C2|Screen on showing a full-screen WHITE image, app force-stopped"
  "C3|Map open, stationary, no navigation. '?debug-info' should read (PAUSED)"
  "C4|Navigating, then '?simulate'. THIS IS THE BASELINE - run it first"
  "C5|Same route with real GPS (no ?simulate)"
  "C6|C4 plus '?no-compass'"
  "C7-20|C4 plus '?nav-fps=20'"
  "C7-10|C4 plus '?nav-fps=10'"
  "C7-5|C4 plus '?nav-fps=5'"
  "C7-1|C4 plus '?nav-fps=1'"
  "C8|C4 with ultra-low-power OLED navigation enabled in Settings > Experimental"
  "C9|Navigating with voice, screen blanked"
  "C10|C4 with a dialog open over the map (do not leave the activity)"
  "C11|C4 plus '?refresh-rate=60'"
  "C12|C4 plus '?gps-interval=1000'"
  "C13|C4 plus '?nav-deadband=1.5'"
)

selected_contains() {
  local needle="$1"
  for s in "${SELECTED[@]}"; do [[ "$s" == "$needle" ]] && return 0; done
  return 1
}

echo "Conditions: $(( ${#SELECTED[@]} ? ${#SELECTED[@]} : ${#CONDITIONS[@]} )), ${REPEATS} repeat(s), ${DURATION}s each"
echo "Output: $OUT_DIR"
echo

for entry in "${CONDITIONS[@]}"; do
  label="${entry%%|*}"
  hint="${entry#*|}"

  if (( ${#SELECTED[@]} )) && ! selected_contains "$label"; then
    continue
  fi

  for (( run = 1; run <= REPEATS; ++run )); do
    echo "================================================================"
    echo " $label  (run $run of $REPEATS)"
    echo " $hint"
    echo "================================================================"
    # Cooldown matters: panel and SoC power both drift with temperature, so a run started on a hot
    # phone is not comparable with one started cold.
    read -r -p "Let the phone settle, set up the condition, then press Enter (s to skip)... " reply
    [[ "$reply" == "s" ]] && { echo "skipped"; continue; }

    "$HERE/run_condition.sh" "$label" "$DURATION" --brightness "$BRIGHTNESS" --no-wait
  done
done

echo
echo "================================================================"
"$HERE/summarize.py" --baseline C4 "$OUT_DIR"/*.csv || true
