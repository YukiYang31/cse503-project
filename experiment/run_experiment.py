#!/usr/bin/env python3
"""
JDK Experiment: Evaluate side-effect analysis on java.util.

1. Extract annotations (ground truth) from JDK source
2. Run the tool on each .java file in java/util/
3. Produce a CSV combining ground truth + tool results

Usage:
    python3 experiment/run_experiment.py                                # full run (resumes from where it left off)
    python3 experiment/run_experiment.py Objects.java                   # single file (re-runs even if cached)
    python3 experiment/run_experiment.py --skip-run                     # only re-generate CSV from existing data
    python3 experiment/run_experiment.py --force                        # force re-run all files (ignore cache)
    python3 experiment/run_experiment.py --skip TreeMap.java            # skip one file
    python3 experiment/run_experiment.py --skip TreeMap.java,HashMap.java  # skip multiple files
"""

import csv
import glob
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

# Paths
JDK_UTIL_DIR = Path("jdk/src/java.base/share/classes/java/util")
JDK_UTIL_PREFIX = "jdk/src/java.base/share/classes/java/util/"
TIMING_DIR = Path("timing")
EXPERIMENT_DIR = Path("experiment")
GROUND_TRUTH_PATH = EXPERIMENT_DIR / "ground_truth.json"
RESULTS_CSV_PATH = EXPERIMENT_DIR / "results.csv"
TOOL_RESULTS_DIR = EXPERIMENT_DIR / "tool_results"

GRADLE_CMD = "./gradlew"
RANDOOP_SEF_PATH = EXPERIMENT_DIR / "Randoop-sef-methods.txt"


def load_randoop_sef():
    """Load the set of method canonical keys that Randoop considers side-effect-free."""
    if not RANDOOP_SEF_PATH.exists():
        print(f"WARNING: {RANDOOP_SEF_PATH} not found, Randoop comparison disabled")
        return set()
    with open(RANDOOP_SEF_PATH) as f:
        return set(line.strip() for line in f if line.strip())


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
    rest = s[colon_idx + 2:]  # "ReturnType methodName(ParamTypes)"

    paren_idx = rest.find('(')
    if paren_idx < 0:
        return None

    before_paren = rest[:paren_idx]
    params_and_close = rest[paren_idx:]  # "(ParamTypes)"

    # Method name is the last token before (
    last_space = before_paren.rfind(' ')
    method_name = before_paren[last_space + 1:] if last_space >= 0 else before_paren

    return f"{class_name}.{method_name}{params_and_close}"


def run_tool_on_file(java_file_path):
    """
    Run the side-effect analysis tool on a single .java file.
    Returns the path to the timing JSON file produced, or None on failure.
    """
    # Get the list of existing timing files before the run
    existing = set()
    if TIMING_DIR.exists():
        existing = set(TIMING_DIR.glob("timing_*.json"))

    args_str = f"{java_file_path} --timing --callgraph-timeout 120 --method-timeout 60"
    cmd = [GRADLE_CMD, "run", f"--args={args_str}"]

    print(f"  Running: {' '.join(cmd)}")
    start_time = time.time()

    try:
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=1800,  # 30 minute safety-net; Java handles granular timeouts internally
        )
        elapsed = time.time() - start_time
        print(f"  Completed in {elapsed:.1f}s (exit code {result.returncode})")

        if result.returncode != 0:
            print(f"  STDERR: {result.stderr[-500:]}" if result.stderr else "  (no stderr)")
            # Still check for timing file — tool may have partially succeeded

    except subprocess.TimeoutExpired:
        elapsed = time.time() - start_time
        print(f"  TIMEOUT after {elapsed:.1f}s")
        return "TIMEOUT"

    # Find the new timing file
    if TIMING_DIR.exists():
        new_files = set(TIMING_DIR.glob("timing_*.json")) - existing
        if new_files:
            # Return the most recent one
            return max(new_files, key=lambda p: p.stat().st_mtime)

    print("  WARNING: No timing JSON produced")
    return None


def load_timing_json(path):
    """Load and return the parsed timing JSON."""
    with open(path, 'r') as f:
        return json.load(f)


def extract_ground_truth():
    """Run the annotation extractor and return the ground truth data."""
    if GROUND_TRUTH_PATH.exists():
        print(f"Loading existing ground truth from {GROUND_TRUTH_PATH}")
        with open(GROUND_TRUTH_PATH) as f:
            return json.load(f)

    print("Extracting ground truth annotations...")
    # Import and run the extractor
    sys.path.insert(0, str(EXPERIMENT_DIR))
    import extract_annotations
    extract_annotations.main()

    with open(GROUND_TRUTH_PATH) as f:
        return json.load(f)


def categorize(jdk_annotation, our_verdict, file_has_annotations):
    """Determine the match category between JDK annotation and tool verdict."""

    if not file_has_annotations:
        return 'File Not Annotated'
   
    has_annotation = jdk_annotation in ('Pure', 'SideEffectFree')

    if our_verdict == 'TIMEOUT':
        return 'Timeout'

    if our_verdict == 'NOT_ANALYZED':
        return 'Not Analyzed'

    if has_annotation and our_verdict == 'SIDE_EFFECT_FREE':
        return 'Match'

    if has_annotation and our_verdict in ('SIDE_EFFECTING', 'GRAPH_VIOLATION'):
        return 'Tool False Positive'

    if not has_annotation and our_verdict == 'SIDE_EFFECT_FREE':
        return 'Annotation Deficit'
      

    if not has_annotation and our_verdict in ('SIDE_EFFECTING', 'GRAPH_VIOLATION'):
        return 'Both Side-Effecting'

    return 'Unknown'


def categorize_randoop_vs_jdk(jdk_annotation, randoop_sef, file_has_annotations):
    """Compare Randoop's SEF verdict against the JDK ground-truth annotation."""
    if not file_has_annotations:
        return 'File Not Annotated'
    has_annotation = jdk_annotation in ('Pure', 'SideEffectFree')
    if has_annotation and randoop_sef:
        return 'Ground Truth Both SEF'
    if has_annotation and not randoop_sef:
        return 'Randoop Miss'
    if not has_annotation and randoop_sef:
        return 'Randoop Extra'
    return 'Ground Truth Both Not SEF'


def categorize_randoop_vs_ours(randoop_sef, our_verdict):
    """Compare Randoop's SEF verdict against our tool's verdict."""
    if our_verdict in ('NOT_ANALYZED', 'TIMEOUT'):
        return 'Not Comparable'
    our_sef = our_verdict == 'SIDE_EFFECT_FREE'
    if randoop_sef and our_sef:
        return 'Both SEF'
    if not randoop_sef and not our_sef:
        return 'Both Side-Effecting'
    if randoop_sef and not our_sef:
        return 'Randoop Only SEF'
    return 'Ours Only SEF'


def build_csv(ground_truth, tool_results_by_file, randoop_sef_set):
    """
    Combine ground truth annotations with tool results into the final CSV.

    tool_results_by_file: dict of filename -> timing JSON data
    """
    # Build annotation lookup: canonical_key -> annotation info
    annotation_map = {}
    for entry in ground_truth:
        key = entry['canonical_key']
        annotation_map[key] = entry

    # Track which files have ANY annotations (vs entirely unannotated files)
    files_with_annotations = set(entry['file'] for entry in ground_truth)

    rows = []

    for filename, timing_data in sorted(tool_results_by_file.items()):
        timed_out = timing_data.get('timedOut', False)
        phase = timing_data.get('phaseTimings', {})
        methods = timing_data.get('methods', [])
        stats = timing_data.get('statistics', {})

        file_total_ms = phase.get('totalMs', 0)
        file_ir_loading_ms = phase.get('irLoadingMs', 0)
        file_call_graph_ms = phase.get('callGraphMs', 0)
        file_dataflow_total_ms = phase.get('dataflowTotalMs', 0)
        file_check_total_ms = phase.get('sideEffectTotalMs', 0)
        file_methods_count = stats.get('methodCount', len(methods))

        # Track which annotation keys have been matched by tool results
        matched_annotation_keys = set()

        for method in methods:
            sig = method.get('signature', '')
            verdict = method.get('verdict', 'NOT_ANALYZED')
            reason = method.get('reason')

            # Build canonical key from SootUp signature
            canonical_key = parse_sootup_signature(sig)

            # Look up annotation
            ann_entry = annotation_map.get(canonical_key) if canonical_key else None
            jdk_annotation = ann_entry['annotation'] if ann_entry else ''

            if ann_entry and canonical_key:
                matched_annotation_keys.add(canonical_key)

            # Parse class name from signature
            class_name = ''
            method_name = ''
            if sig.startswith('<') and ': ' in sig:
                class_name = sig[1:sig.index(': ')]
                rest = sig[sig.index(': ') + 2:-1]
                paren_pos = rest.find('(')
                if paren_pos >= 0:
                    before = rest[:paren_pos]
                    space = before.rfind(' ')
                    method_name = before[space + 1:] if space >= 0 else before

            cat = categorize(jdk_annotation, verdict, filename in files_with_annotations)

            randoop_sef = canonical_key in randoop_sef_set if canonical_key else False
            randoop_vs_jdk = categorize_randoop_vs_jdk(jdk_annotation, randoop_sef, filename in files_with_annotations)
            randoop_vs_ours = categorize_randoop_vs_ours(randoop_sef, verdict)

            rows.append({
                'file': filename,
                'class': class_name,
                'method': method_name,
                'signature': sig,
                'jdk_annotation': jdk_annotation,
                'our_verdict': verdict,
                'reason': reason or '',
                'category': cat,
                'randoop_sef': randoop_sef,
                'randoop_vs_jdk': randoop_vs_jdk,
                'randoop_vs_ours': randoop_vs_ours,
                'dataflow_ms': method.get('dataflowMs', 0),
                'check_ms': method.get('sideEffectMs', 0),
                'total_method_ms': method.get('totalMs', 0),
                'jimple_stmts': method.get('jimpleStmtCount', 0),
                'graph_nodes': method.get('graphNodes', 0),
                'graph_edges': method.get('graphEdges', 0),
                'file_total_ms': file_total_ms,
                'file_ir_loading_ms': file_ir_loading_ms,
                'file_call_graph_ms': file_call_graph_ms,
                'file_dataflow_total_ms': file_dataflow_total_ms,
                'file_check_total_ms': file_check_total_ms,
                'file_methods_count': file_methods_count,
            })

        # Add rows for annotated methods NOT found in tool output (not analyzed)
        for entry in ground_truth:
            if entry['file'] != filename:
                continue
            key = entry['canonical_key']
            if key in matched_annotation_keys:
                continue
            if timed_out:
                unmatched_verdict = 'TIMEOUT'
                unmatched_reason = 'file timed out'
            elif not entry['has_body']:
                unmatched_verdict = 'NOT_ANALYZED'
                unmatched_reason = 'abstract/interface method'
            else:
                unmatched_verdict = 'NOT_ANALYZED'
                unmatched_reason = 'not in tool output'

            jdk_annotation = entry['annotation']
            unmatched_randoop_sef = entry['canonical_key'] in randoop_sef_set
            rows.append({
                'file': filename,
                'class': entry['class_name'],
                'method': entry['method_name'],
                'signature': '',
                'jdk_annotation': jdk_annotation,
                'our_verdict': unmatched_verdict,
                'reason': unmatched_reason,
                'category': categorize(jdk_annotation, unmatched_verdict, filename in files_with_annotations),
                'randoop_sef': unmatched_randoop_sef,
                'randoop_vs_jdk': categorize_randoop_vs_jdk(jdk_annotation, unmatched_randoop_sef, filename in files_with_annotations),
                'randoop_vs_ours': categorize_randoop_vs_ours(unmatched_randoop_sef, unmatched_verdict),
                'dataflow_ms': 0,
                'check_ms': 0,
                'total_method_ms': 0,
                'jimple_stmts': 0,
                'graph_nodes': 0,
                'graph_edges': 0,
                'file_total_ms': file_total_ms,
                'file_ir_loading_ms': file_ir_loading_ms,
                'file_call_graph_ms': file_call_graph_ms,
                'file_dataflow_total_ms': file_dataflow_total_ms,
                'file_check_total_ms': file_check_total_ms,
                'file_methods_count': file_methods_count,
            })

    return rows


def write_csv(rows, path):
    """Write rows to the results CSV."""
    fieldnames = [
        'file', 'class', 'method', 'signature', 'jdk_annotation',
        'our_verdict', 'reason', 'category',
        'randoop_sef', 'randoop_vs_jdk', 'randoop_vs_ours',
        'dataflow_ms', 'check_ms', 'total_method_ms',
        'jimple_stmts', 'graph_nodes', 'graph_edges',
        'file_total_ms', 'file_ir_loading_ms', 'file_call_graph_ms',
        'file_dataflow_total_ms', 'file_check_total_ms', 'file_methods_count',
    ]

    with open(path, 'w', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)

    print(f"\nResults written to: {path}")
    print(f"Total rows: {len(rows)}")


def print_summary(rows):
    """Print a summary table to stdout."""
    # Category breakdown
    categories = {}
    for row in rows:
        cat = row['category']
        categories[cat] = categories.get(cat, 0) + 1

    print("\n" + "=" * 60)
    print("EXPERIMENT SUMMARY")
    print("=" * 60)

    print(f"\nTotal methods in CSV: {len(rows)}")
    print("\nCategory Breakdown:")
    for cat in ['Match', 'Tool False Positive', 'Annotation Deficit',
                'File Not Annotated', 'Both Side-Effecting', 'Not Analyzed', 'Timeout', 'Unknown']:
        count = categories.get(cat, 0)
        if count > 0:
            print(f"  {cat:25s}: {count:5d}")

    # Precision/recall for annotated methods
    annotated_rows = [r for r in rows if r['jdk_annotation'] in ('Pure', 'SideEffectFree')]
    analyzed_annotated = [r for r in annotated_rows if r['our_verdict'] != 'NOT_ANALYZED']

    if analyzed_annotated:
        matches = sum(1 for r in analyzed_annotated if r['category'] == 'Match')
        false_pos = sum(1 for r in analyzed_annotated if r['category'] == 'Tool False Positive')
        total = len(analyzed_annotated)
        print(f"\nAnnotated methods analyzed: {total}")
        print(f"  Matches (tool agrees):     {matches} ({100*matches/total:.1f}%)")
        print(f"  Tool False Positives:      {false_pos} ({100*false_pos/total:.1f}%)")

    # Randoop vs JDK ground truth
    randoop_annotated = [r for r in rows if r['jdk_annotation'] in ('Pure', 'SideEffectFree')]
    if randoop_annotated:
        print(f"\nRandoop vs JDK Annotations (annotated methods only: {len(randoop_annotated)}):")
        for cat in ['Ground Truth Both SEF', 'Randoop Miss', 'Randoop Extra', 'Ground TruthBoth Not SEF']:
            count = sum(1 for r in randoop_annotated if r['randoop_vs_jdk'] == cat)
            if count > 0:
                print(f"  {cat:25s}: {count:5d}")

    # Randoop vs our tool (on methods both analyze)
    comparable = [r for r in rows if r['randoop_vs_ours'] != 'Not Comparable']
    if comparable:
        print(f"\nRandoop vs Our Tool ({len(comparable)} methods comparable):")
        for cat in ['Both SEF', 'Both Side-Effecting', 'Randoop Only SEF', 'Ours Only SEF']:
            count = sum(1 for r in comparable if r['randoop_vs_ours'] == cat)
            if count > 0:
                print(f"  {cat:25s}: {count:5d}")

        # Methods where both Randoop and JDK checker agree it's side-effecting, but our tool says SEF
        both_say_se_ours_say_sef = [
            r for r in comparable
            if not r['randoop_sef']
            and r['jdk_annotation'] not in ('Pure', 'SideEffectFree')
            and r['our_verdict'] == 'SIDE_EFFECT_FREE'
        ]
        print(f"\n  Randoop=SE + Checker=SE + Ours=SEF (potential over-approx): {len(both_say_se_ours_say_sef)}")

    # Timing stats
    analyzed = [r for r in rows if r['our_verdict'] != 'NOT_ANALYZED']
    if analyzed:
        total_ms = sum(r['total_method_ms'] for r in analyzed)
        avg_ms = total_ms / len(analyzed)
        print(f"\nTiming (analyzed methods):")
        print(f"  Total analysis time:       {total_ms:.1f} ms")
        print(f"  Average per method:        {avg_ms:.2f} ms")
        print(f"  Methods analyzed:          {len(analyzed)}")

    # Per-file timing
    files_seen = set()
    file_times = []
    for r in rows:
        fname = r['file']
        if fname not in files_seen and r['file_total_ms'] > 0:
            files_seen.add(fname)
            file_times.append(r['file_total_ms'])
    if file_times:
        print(f"\nPer-file pipeline timing:")
        print(f"  Files processed:           {len(file_times)}")
        print(f"  Total pipeline time:       {sum(file_times):.1f} ms")
        print(f"  Average per file:          {sum(file_times)/len(file_times):.1f} ms")


def save_tool_result(filename, timing_json_path):
    """Copy the timing JSON to experiment/tool_results/ for reproducibility."""
    TOOL_RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    dest = TOOL_RESULTS_DIR / f"{filename}.json"
    import shutil
    shutil.copy2(timing_json_path, dest)
    return dest


def load_existing_tool_results():
    """Load previously saved tool results from experiment/tool_results/."""
    results = {}
    if TOOL_RESULTS_DIR.exists():
        for json_path in sorted(TOOL_RESULTS_DIR.glob("*.java.json")):
            filename = json_path.name.replace('.json', '')
            with open(json_path) as f:
                results[filename] = json.load(f)
    return results


def main():
    script_dir = Path(__file__).parent
    project_dir = script_dir.parent
    os.chdir(project_dir)

    # Parse arguments
    skip_run = '--skip-run' in sys.argv
    force_run = '--force' in sys.argv
    file_filter = None
    skip_files = set()

    args = sys.argv[1:]
    i = 0
    while i < len(args):
        if args[i] == '--skip' and i + 1 < len(args):
            # Accept comma-separated list or space-separated values
            for name in args[i + 1].split(','):
                name = name.strip()
                if name:
                    skip_files.add(name)
            i += 2
        elif not args[i].startswith('--'):
            if file_filter is None:
                file_filter = args[i]
            i += 1
        else:
            i += 1

    # Step 1: Extract ground truth and load Randoop SEF list
    ground_truth = extract_ground_truth()
    print(f"Ground truth: {len(ground_truth)} annotated methods")
    randoop_sef_set = load_randoop_sef()
    java_util_randoop = sum(1 for k in randoop_sef_set if k.startswith('java.util.'))
    print(f"Randoop SEF list: {len(randoop_sef_set)} methods total, {java_util_randoop} from java.util")

    # Step 2: Run the tool on each file
    java_files = sorted(JDK_UTIL_DIR.glob("*.java"))
    if file_filter:
        java_files = [f for f in java_files if f.name == file_filter or file_filter in f.name]
        if not java_files:
            print(f"Error: No files matching '{file_filter}' found in {JDK_UTIL_DIR}")
            sys.exit(1)

    if skip_files:
        before = len(java_files)
        java_files = [f for f in java_files if f.name not in skip_files]
        skipped_count = before - len(java_files)
        print(f"Skipping {skipped_count} file(s): {', '.join(sorted(skip_files))}")

    print(f"\nTarget files: {len(java_files)}")

    tool_results_by_file = {}

    if skip_run:
        print("Skipping tool runs (--skip-run), loading existing results...")
        tool_results_by_file = load_existing_tool_results()
        if not tool_results_by_file:
            print("No existing results found in experiment/tool_results/")
            sys.exit(1)
        print(f"Loaded results for {len(tool_results_by_file)} files")
    else:
        # Load existing cached results for resume support
        cached_results = load_existing_tool_results()

        # When a specific file is requested, always re-run it (ignore cache)
        # When running the full experiment, skip files that already have cached results
        use_cache = not force_run and not file_filter

        if use_cache and cached_results:
            print(f"Found cached results for {len(cached_results)} files (use --force to re-run all)")

        total_files = len(java_files)
        skipped = 0
        for idx, java_file in enumerate(java_files):
            # Resume support: skip files that already have results
            if use_cache and java_file.name in cached_results:
                tool_results_by_file[java_file.name] = cached_results[java_file.name]
                skipped += 1
                print(f"  [{idx + 1}/{total_files}] {java_file.name} — cached ✓")
                continue

            print(f"\n[{idx + 1}/{total_files}] Processing {java_file.name}...")

            timing_path = run_tool_on_file(str(java_file))
            if timing_path == "TIMEOUT":
                timeout_data = {
                    'timedOut': True,
                    'methods': [],
                    'phaseTimings': {},
                    'statistics': {},
                }
                tool_results_by_file[java_file.name] = timeout_data
                TOOL_RESULTS_DIR.mkdir(parents=True, exist_ok=True)
                dest = TOOL_RESULTS_DIR / f"{java_file.name}.json"
                with open(dest, 'w') as f:
                    json.dump(timeout_data, f, indent=2)
                print(f"  Saved timeout marker to {dest}")
            elif timing_path:
                timing_data = load_timing_json(timing_path)
                tool_results_by_file[java_file.name] = timing_data
                save_tool_result(java_file.name, timing_path)
            else:
                print(f"  SKIPPED: No results for {java_file.name}")

        if skipped:
            print(f"\nResumed: {skipped} files from cache, {total_files - skipped} freshly processed")
        print(f"Tool completed for {len(tool_results_by_file)}/{total_files} files")

        # When re-running a single file, merge its result back into the full cached set
        # so the CSV stays complete with all previously processed files
        if file_filter and cached_results:
            merged = dict(cached_results)
            merged.update(tool_results_by_file)  # overwrite with fresh result
            tool_results_by_file = merged
            print(f"Merged with cached results: CSV will contain {len(tool_results_by_file)} files total")

    # Step 3: Build and write CSV
    rows = build_csv(ground_truth, tool_results_by_file, randoop_sef_set)
    if force_run:
        ts = time.strftime("%Y%m%d_%H%M%S")
        csv_path = EXPERIMENT_DIR / f"results_{ts}.csv"
    else:
        csv_path = RESULTS_CSV_PATH
    write_csv(rows, csv_path)
    print_summary(rows)


if __name__ == '__main__':
    main()
