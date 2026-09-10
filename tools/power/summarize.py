#!/usr/bin/env python3
"""Aggregates fuel-gauge CSVs written by sample_power.sh into per-condition power figures.

Reports the median rather than the mean: fuel-gauge traces contain occasional wild samples (gauge
re-syncs, a background job waking up), and a median with an interquartile range describes a noisy
steady state far more honestly than a mean with a standard deviation.

Usage: summarize.py [--warmup 60] [--baseline C4] power-runs/*.csv
"""

import argparse
import csv
import glob
import statistics
import sys
from collections import defaultdict


def detect_scale(median_abs, big, mid):
    """Vendors report current in either uA or mA and voltage in either uV or mV, with no marker.
    Pick the divisor that puts the reading into a physically sane range."""
    if median_abs > big:
        return 1e6
    if median_abs > mid:
        return 1e3
    return 1.0


def load(paths, warmup):
    rows = defaultdict(list)
    for path in paths:
        with open(path, newline="") as f:
            reader = csv.DictReader(r for r in f if not r.startswith("#"))
            samples = [r for r in reader if r.get("current_raw") not in (None, "NA", "")]
        if not samples:
            print(f"warning: {path} has no usable samples", file=sys.stderr)
            continue
        t0 = int(samples[0]["timestamp"])
        for r in samples:
            if int(r["timestamp"]) - t0 < warmup:
                continue
            rows[r["label"]].append(r)
    return rows


def summarize(label, samples):
    cur_raw = [abs(float(r["current_raw"])) for r in samples]
    vol_raw = [abs(float(r["voltage_raw"])) for r in samples]
    if not cur_raw:
        return None

    cur_div = detect_scale(statistics.median(cur_raw), 20_000, 20)
    vol_div = detect_scale(statistics.median(vol_raw), 100_000, 100)

    mw = [(c / cur_div) * (v / vol_div) * 1000.0 for c, v in zip(cur_raw, vol_raw)]
    mw.sort()
    if len(mw) > 3:
        quartiles = statistics.quantiles(mw, n=4)
        q1, q3 = quartiles[0], quartiles[2]
    else:
        q1, q3 = mw[0], mw[-1]

    temps = [float(r["temp_decic"]) / 10.0 for r in samples if r.get("temp_decic") not in (None, "NA", "")]
    caps = [int(r["capacity"]) for r in samples if r.get("capacity") not in (None, "NA", "")]

    return {
        "label": label,
        "n": len(mw),
        "median_mw": statistics.median(mw),
        "iqr": q3 - q1,
        "temp_c": statistics.median(temps) if temps else float("nan"),
        "soc": f"{max(caps)}->{min(caps)}%" if caps else "?",
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("csvs", nargs="+")
    ap.add_argument("--warmup", type=int, default=60,
                    help="seconds to discard at the start of each run (default 60)")
    ap.add_argument("--baseline", default=None,
                    help="label to express other conditions as a delta from")
    args = ap.parse_args()

    paths = [p for pat in args.csvs for p in glob.glob(pat)]
    rows = load(paths, args.warmup)
    if not rows:
        sys.exit("no samples found")

    results = [s for s in (summarize(k, v) for k, v in sorted(rows.items())) if s]

    print(f"{'condition':<16}{'n':>6}{'median mW':>12}{'IQR':>9}{'temp C':>9}{'SoC':>12}")
    print("-" * 64)
    for r in results:
        print(f"{r['label']:<16}{r['n']:>6}{r['median_mw']:>12.0f}{r['iqr']:>9.0f}"
              f"{r['temp_c']:>9.1f}{r['soc']:>12}")

    if args.baseline:
        base = next((r for r in results if r["label"] == args.baseline), None)
        if not base:
            sys.exit(f"baseline '{args.baseline}' not among {[r['label'] for r in results]}")
        print(f"\ndelta vs {args.baseline} ({base['median_mw']:.0f} mW)")
        print("-" * 64)
        for r in results:
            if r["label"] == args.baseline:
                continue
            d = r["median_mw"] - base["median_mw"]
            print(f"{r['label']:<16}{d:>+12.0f} mW  ({d / base['median_mw'] * 100:+.1f}%)")

    noisy = [r for r in results if r["median_mw"] and r["iqr"] / r["median_mw"] > 0.25]
    if noisy:
        print("\nwarning: IQR exceeds 25% of the median for "
              f"{', '.join(r['label'] for r in noisy)} — treat those as indicative only "
              "and re-run with a longer duration or a quieter device.")


if __name__ == "__main__":
    main()
