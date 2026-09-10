# Measuring power draw on Android

This describes how to get repeatable power numbers out of a physical Android device, and the
attribution matrix used to work out where the battery actually goes during navigation. It exists
because "this should save battery" is very easy to assert and surprisingly hard to demonstrate.

## Why not a USB power meter

An inline USB meter measures *charging* current. That is dominated by the charger's negotiation and
the battery's own charge curve, and it masks the system draw we care about by an order of
magnitude. Use the phone's own fuel gauge instead, with the device running on battery:

```sh
adb tcpip 5555
adb connect <phone-ip>:5555
# now physically unplug USB
```

`tools/power/sample_power.sh` refuses to run if the battery reports `Charging` or `Full`, because
readings taken while charging measure the charger.

A USB meter is still useful as an *independent cross-check* of the display claim specifically:
black vs. normal map content at a fixed brightness is a large, immediate delta that survives
charging noise.

## The tools

| Script | Does |
|---|---|
| `tools/power/run_matrix.sh` | Walks the whole matrix, prompting per condition, then summarises. **Start here.** |
| `tools/power/prepare_device.sh [brightness]` | Pins brightness, disables adaptive brightness, extends the screen timeout, reports anything it could not set |
| `tools/power/sample_power.sh <label> [seconds] [dir]` | Samples `current_now` / `voltage_now` / `temp` at 1 Hz into a CSV |
| `tools/power/run_condition.sh <label> [seconds]` | Prepares the device, waits for the operator to set up the condition, then samples |
| `tools/power/summarize.py --baseline C4 power-runs/*.csv` | Median mW with IQR per condition, plus deltas |

Run the matrix in two passes. The full thing at measurement-grade settings is 13 conditions x 3
repeats x 10 minutes, which is over six hours of sitting with a phone, and most of it would be spent
on levers that turn out not to matter:

```sh
tools/power/run_matrix.sh                 # triage: every condition once, 5 min each (~90 min)
tools/power/run_matrix.sh --full C4 C13   # then 3 x 10 min on whatever survived
```

Triage numbers are for ranking levers, not for quoting. Anything you would put in a commit message
should come from a `--full` run.

Sampling runs inside a single persistent `adb shell` loop. Spawning one `adb` process per sample
would itself cost more power than several of the effects being measured.

Sysfs units are not self-describing — vendors report current in either µA or mA and voltage in
either µV or mV — so `summarize.py` infers the scale from the magnitude of the readings.

## Protocol

Every one of these matters; skipping them is what produces measurements that disagree run to run.

- **Fixed brightness**, adaptive brightness off. The display usually dominates the budget, so a
  drifting backlight swamps everything else.
- **Fixed radio state**: mobile data and Bluetooth off, WiFi on. WiFi must stay on for adb over the
  network; it is a constant offset across all conditions, which is fine.
- **Fixed state-of-charge band** — run only between, say, 80 % and 40 %. Cell voltage and gauge
  behaviour both change near the ends of the range.
- **Thermal settling**: let the phone cool to a stable temperature between runs. Panel and SoC power
  both drift with temperature, and a hot device throttles.
- **≥10 minutes per run, 3 repeats**, discarding the first 60 s. Report the **median with an IQR**,
  never a mean: fuel-gauge traces contain occasional wild samples, and a mean hides that.
- **Identical route, region and zoom** across every run. Use `?simulate` rather than real GPS
  wherever the condition allows: it replays the route at a constant speed from a simulated provider,
  so the movement is identical between runs instead of varying with the GNSS fix quality. The cost of
  the GNSS hardware is then measured separately, as C5 − C4.

`summarize.py` prints a warning when a condition's IQR exceeds 25 % of its median — treat those
results as indicative only.

## The attribution matrix

Each condition ~10 min, 3 repeats. Conditions are switched with debug commands typed into the
app's search box (see [DEBUG_COMMANDS.md](DEBUG_COMMANDS.md)) so that one install runs the whole
matrix without rebuilding.

| # | Condition | Isolates | How to set it |
|---|---|---|---|
| C0 | Screen off, app killed | System floor | `adb shell input keyevent SLEEP`, force-stop the app |
| C1 | Screen on, all-black image, app killed | Panel floor at this brightness | Any full-screen black image viewer |
| C2 | Screen on, all-white image | Panel ceiling → the panel's OLED dynamic range | Same, white |
| C3 | App foreground, map static, no GPS | Whether the idle-suspend path really costs ~0 | Open the map, do not touch it; `?debug-info` should show `(PAUSED)` |
| C4 | Simulated navigation, default style | **Baseline navigation cost** | Start navigating, then `?simulate` |
| C5 | Real-GPS navigation | GNSS delta (C5 − C4) | Same route with real GPS |
| C6 | C4 with the compass unregistered | Sensor delta | `?no-compass` |
| C7 | C4 at 20/10/5/1 fps caps | Frame-rate sensitivity curve | `?nav-fps=20` etc. |
| C8 | C4 with ultra-low-power OLED navigation enabled | OLED + minimal-rendering delta | Enable it under Settings > Experimental |
| C9 | Screen off, navigating with voice only | Absolute navigation floor | Start navigation, then blank the screen |
| C10 | C4 with a dialog over the map | Cost of the paused-surface render spin | Open any dialog over the map without leaving the activity |
| C11 | C4 at forced 120/60 Hz panel refresh | Panel and compositor rate | `?refresh-rate=60`, or developer options |
| C12 | C4 with the location rate lowered | GNSS + wakeup + animation-churn delta | `?gps-interval=1000` |
| C13 | C4 with the redraw deadband on | Skipping redraws nobody can see | `?nav-deadband=1.5` |

### Measuring an alternative map palette

C8 measures the complete low-power mode: true-black output, sparse navigation UI, a dim line-only
base-map context layer and a 15 FPS cap. Area fills, labels, buildings, traffic and map overlays are not
drawn. To isolate palette changes instead, `StyleReader::ReadDrawingRulesOverride`
loads `<writable dir>/styles/drules_<family>.bin` in preference to the bundled file:

```sh
adb push drules_outdoors.bin /sdcard/Android/data/<package>/files/styles/drules_outdoors.bin
```

See [STYLES.md](STYLES.md) for the override path and how to regenerate the binary.

C7 is confounded by C11: capping the render loop while the panel still refreshes at 120 Hz
understates the saving. Pin the refresh rate with `?refresh-rate` when running the frame-rate curve.

C13 is not a pure win to be confirmed but a trade to be measured. The deadband can only skip a
redraw if the previous frame can be re-composited, and that requires post-processing, which is
switched off during navigation precisely because it costs a fullscreen composite per frame. So
`?nav-deadband` turns both on together: it wins only if the frames it skips outweigh the composite
it adds. Read it against C4 at the same `?nav-fps`, and expect the answer to depend on the cap.

## Explaining the numbers

Power figures say *how much*; these say *why*:

- `?debug-info` in the search box shows zoom and FPS, and appends `(PAUSED)` when the render loop
  has suspended itself — a direct readout of whether on-demand rendering is working.
- `SHOW_FRAMES_STATS` in `libs/drape/drape_diagnostics.hpp` logs active vs. scene-skipped frame
  counts every 5 s. It is a commented-out `#define`, so enable it once for the whole measurement
  phase and rebuild.
- Perfetto traces pick up drape's `TRACE_SECTION` markers (`RenderFrame`, `RenderScene`,
  `PrepareScene`, …) when built with `enableTrace=ON` in `android/gradle.properties`.
- `adb shell dumpsys gfxinfo <package> framestats` for frame delivery.
- `adb shell dumpsys display | grep -i fps` for the panel's actual refresh rate.
- GPU busy: `/sys/class/kgsl/kgsl-3d0/gpubusy` on Adreno; Mali exposes different counters.
