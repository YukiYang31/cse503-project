#!/usr/bin/env python3
"""
Compare jdk-cache summaries against JDK checker annotations (ground_truth.json)
and Randoop SEF list (Randoop-sef-methods.txt).

No tool runs needed — just reads existing cache files.

Usage:
    python3 experiment/compare_cache.py
    python3 experiment/compare_cache.py --csv  # also write experiment/cache_comparison.csv
"""

import csv
import glob
import json
import os
import sys
from pathlib import Path

EXPERIMENT_DIR = Path("experiment")
GROUND_TRUTH_PATH = EXPERIMENT_DIR / "ground_truth.json"
RANDOOP_SEF_PATH = EXPERIMENT_DIR / "Randoop-sef-methods.txt"
JDK_CACHE_DIR = Path("jdk-cache")
CSV_PATH = EXPERIMENT_DIR / "cache_comparison.csv"


def parse_sootup_signature(sig):
    """
    Parse a SootUp signature into a canonical key.
    E.g., '<java.util.ArrayList: int size()>' -> 'java.util.ArrayList.size()'
    """
    s = sig.strip()
    if s.startswith('<') and s.endswith('>'):
        s = s[1:-1]

    colon_idx = s.find(': ')
    if colon_idx < 0:
        return None

    class_name = s[:colon_idx]
    rest = s[colon_idx + 2:]

    paren_idx = rest.find('(')
    if paren_idx < 0:
        return None

    before_paren = rest[:paren_idx]
    params_and_close = rest[paren_idx:]

    last_space = before_paren.rfind(' ')
    method_name = before_paren[last_space + 1:] if last_space >= 0 else before_paren

    return f"{class_name}.{method_name}{params_and_close}"


def load_cache():
    """Load all jdk-cache entries. Returns dict of canonical_key -> {sig, result, reasons}."""
    cache = {}
    for json_path in glob.glob(str(JDK_CACHE_DIR / "*.json")):
        try:
            with open(json_path) as f:
                data = json.load(f)
        except (json.JSONDecodeError, IOError):
            continue
        sig = data.get("sig", "")
        canonical = parse_sootup_signature(sig)
        if canonical:
            cache[canonical] = {
                "sig": sig,
                "result": data.get("result", "UNKNOWN"),
                "reasons": data.get("reasons", []),
            }
    return cache


def load_ground_truth():
    """Load ground_truth.json. Returns dict of canonical_key -> entry."""
    with open(GROUND_TRUTH_PATH) as f:
        entries = json.load(f)
    return {e["canonical_key"]: e for e in entries}


def load_randoop_sef():
    """Load Randoop SEF set."""
    if not RANDOOP_SEF_PATH.exists():
        print(f"WARNING: {RANDOOP_SEF_PATH} not found")
        return set()
    with open(RANDOOP_SEF_PATH) as f:
        return set(line.strip() for line in f if line.strip())


def main():
    write_csv = "--csv" in sys.argv

    cache = load_cache()
    gt = load_ground_truth()
    randoop_sef = load_randoop_sef()

    print(f"jdk-cache entries:       {len(cache)}")
    print(f"Ground truth entries:    {len(gt)} (annotated methods across java.util)")
    print(f"Randoop SEF entries:     {len(randoop_sef)}")

    # Find overlap: cache keys that exist in ground truth
    cache_keys = set(cache.keys())
    gt_keys = set(gt.keys())
    overlap = cache_keys & gt_keys
    cache_only = cache_keys - gt_keys
    gt_only = gt_keys - cache_keys

    print(f"\nOverlap (in both cache & ground truth): {len(overlap)}")
    print(f"Cache only (no annotation):             {len(cache_only)}")
    print(f"Ground truth only (not in cache):       {len(gt_only)}")

    # Categorize overlapping methods
    rows = []
    for key in sorted(overlap):
        entry = gt[key]
        cached = cache[key]
        jdk_ann = entry["annotation"]  # 'Pure', 'SideEffectFree', or ''
        cache_result = cached["result"]  # 'SIDE_EFFECT_FREE' or 'SIDE_EFFECTING'
        in_randoop = key in randoop_sef

        has_annotation = jdk_ann in ("Pure", "SideEffectFree")
        cache_sef = cache_result == "SIDE_EFFECT_FREE"

        # Cache vs JDK annotation
        if has_annotation and cache_sef:
            cat = "Match"
        elif has_annotation and not cache_sef:
            cat = "Annotated Mismatch"  # tool more conservative than annotation
        elif not has_annotation and cache_sef:
            cat = "Unannotated Mismatch"  # potential unsoundness
        else:
            cat = "Both Side-Effecting"

        # Cache vs Randoop
        if cache_sef and in_randoop:
            randoop_cat = "Match (Both SEF)"
        elif not cache_sef and not in_randoop:
            randoop_cat = "Both Side-Effecting"
        elif in_randoop and not cache_sef:
            randoop_cat = "Randoop Only SEF"
        else:
            randoop_cat = "Ours Only SEF"

        # Three-way: JDK + Randoop both say SE but we say SEF
        red_alert = (not has_annotation and not in_randoop and cache_sef)

        reasons = "; ".join(cached["reasons"][:3])
        if len(cached["reasons"]) > 3:
            reasons += f" ... (+{len(cached['reasons']) - 3} more)"

        rows.append({
            "canonical_key": key,
            "class": entry["class_name"],
            "method": entry["method_name"],
            "file": entry["file"],
            "jdk_annotation": jdk_ann,
            "cache_result": cache_result,
            "randoop_sef": in_randoop,
            "category": cat,
            "randoop_vs_cache": randoop_cat,
            "red_alert": red_alert,
            "reasons": reasons,
        })

    # Also include ground-truth methods NOT in cache
    for key in sorted(gt_only):
        entry = gt[key]
        in_randoop = key in randoop_sef
        rows.append({
            "canonical_key": key,
            "class": entry["class_name"],
            "method": entry["method_name"],
            "file": entry["file"],
            "jdk_annotation": entry["annotation"],
            "cache_result": "NOT_IN_CACHE",
            "randoop_sef": in_randoop,
            "category": "Not In Cache",
            "randoop_vs_cache": "N/A",
            "red_alert": False,
            "reasons": "",
        })

    # Print summary
    print("\n" + "=" * 60)
    print("CACHE vs GROUND TRUTH (annotated methods)")
    print("=" * 60)

    cats = {}
    for r in rows:
        cats[r["category"]] = cats.get(r["category"], 0) + 1
    for cat in ["Match", "Annotated Mismatch", "Unannotated Mismatch",
                 "Both Side-Effecting", "Not In Cache"]:
        count = cats.get(cat, 0)
        if count > 0:
            print(f"  {cat:25s}: {count:5d}")

    # Precision on annotated methods in cache
    annotated_in_cache = [r for r in rows if r["jdk_annotation"] in ("Pure", "SideEffectFree")
                          and r["cache_result"] != "NOT_IN_CACHE"]
    if annotated_in_cache:
        matches = sum(1 for r in annotated_in_cache if r["category"] == "Match")
        total = len(annotated_in_cache)
        print(f"\n  Annotated methods in cache: {total}")
        print(f"  Matches:                    {matches} ({100*matches/total:.1f}%)")
        print(f"  Annotated Mismatches:       {total - matches} ({100*(total-matches)/total:.1f}%)")

    # Annotated Mismatch details
    ann_mismatches = [r for r in rows if r["category"] == "Annotated Mismatch"]
    if ann_mismatches:
        print(f"\n--- Annotated Mismatches (tool says SE, JDK says SEF) ---")
        # for r in ann_mismatches:
        #     print(f"  {r['canonical_key']}")
        #     print(f"    annotation={r['jdk_annotation']}  randoop_sef={r['randoop_sef']}")
        #     print(f"    reasons: {r['reasons']}")

    # Unannotated Mismatch details
    unann_mismatches = [r for r in rows if r["category"] == "Unannotated Mismatch"]
    if unann_mismatches:
        print(f"\n--- Unannotated Mismatches (tool says SEF, no JDK annotation) ---")
        # for r in unann_mismatches:
        #     randoop_flag = " [Randoop also SEF]" if r["randoop_sef"] else " [Randoop says SE]"
        #     print(f"  {r['canonical_key']}{randoop_flag}")

    # Red alerts
    red_alerts = [r for r in rows if r["red_alert"]]
    if red_alerts:
        print(f"\n{'!'*60}")
        print(f"RED ALERT: {len(red_alerts)} methods where JDK=no annotation + Randoop=SE + Cache=SEF")
        print(f"{'!'*60}")
        for r in red_alerts:
            print(f"  {r['canonical_key']}")
    else:
        print(f"\nNo red alerts (good — no case where both JDK and Randoop say SE but we say SEF)")

    # Cache vs Randoop
    print("\n" + "=" * 60)
    print("CACHE vs RANDOOP")
    print("=" * 60)
    comparable = [r for r in rows if r["cache_result"] != "NOT_IN_CACHE"]
    rcats = {}
    for r in comparable:
        rcats[r["randoop_vs_cache"]] = rcats.get(r["randoop_vs_cache"], 0) + 1
    for cat in ["Match (Both SEF)", "Randoop Only SEF", "Ours Only SEF", "Both Side-Effecting"]:
        count = rcats.get(cat, 0)
        if count > 0:
            print(f"  {cat:25s}: {count:5d}")

    # Overall cache stats
    print("\n" + "=" * 60)
    print("OVERALL CACHE STATS")
    print("=" * 60)
    all_sef = sum(1 for v in cache.values() if v["result"] == "SIDE_EFFECT_FREE")
    all_se = sum(1 for v in cache.values() if v["result"] == "SIDE_EFFECTING")
    print(f"  Total cached methods:      {len(cache)}")
    print(f"  SIDE_EFFECT_FREE:          {all_sef} ({100*all_sef/len(cache):.1f}%)")
    print(f"  SIDE_EFFECTING:            {all_se} ({100*all_se/len(cache):.1f}%)")

    # java.util breakdown
    util_cache = {k: v for k, v in cache.items() if k.startswith("java.util.")}
    util_sef = sum(1 for v in util_cache.values() if v["result"] == "SIDE_EFFECT_FREE")
    print(f"\n  java.util cached methods:  {len(util_cache)}")
    print(f"  java.util SEF:             {util_sef} ({100*util_sef/len(util_cache):.1f}%)" if util_cache else "")

    if write_csv:
        fieldnames = [
            "canonical_key", "class", "method", "file", "jdk_annotation",
            "cache_result", "randoop_sef", "category", "randoop_vs_cache",
            "red_alert", "reasons",
        ]
        with open(CSV_PATH, "w", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=fieldnames)
            writer.writeheader()
            writer.writerows(rows)
        print(f"\nCSV written to: {CSV_PATH} ({len(rows)} rows)")


if __name__ == "__main__":
    main()
