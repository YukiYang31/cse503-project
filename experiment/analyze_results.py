#!/usr/bin/env python3
"""Compute all requested statistics from experiment results.csv for LaTeX."""

import csv
import statistics
import math
from collections import Counter, defaultdict

CSV_PATH = "/Users/yukiyang/Desktop/yukis_very_important_folder/UW/CSE503/project/experiment/results.csv"

rows = []
with open(CSV_PATH, newline='', encoding='utf-8') as f:
    reader = csv.DictReader(f)
    for r in reader:
        rows.append(r)

total = len(rows)

# ─── 1. Per-category breakdown ───────────────────────────────────────────────
print("=" * 70)
print("1. PER-CATEGORY BREAKDOWN")
print("=" * 70)
cat_counts = Counter(r['category'] for r in rows)
for cat in sorted(cat_counts, key=lambda c: -cat_counts[c]):
    n = cat_counts[cat]
    pct = 100.0 * n / total
    print(f"  {cat:30s}  {n:5d}  ({pct:5.1f}%)")
print(f"  {'TOTAL':30s}  {total:5d}")
print()

# ─── 2. Timing statistics ────────────────────────────────────────────────────
print("=" * 70)
print("2. TIMING STATISTICS")
print("=" * 70)

analyzed = [r for r in rows if float(r['dataflow_ms']) > 0]
total_method_times = [float(r['total_method_ms']) for r in analyzed]

print(f"\n  Analyzed methods (dataflow_ms > 0): {len(analyzed)}")
print(f"  Total methods: {total}")
print()

if total_method_times:
    print("  Per-method total_method_ms (analyzed only):")
    print(f"    Min:    {min(total_method_times):.2f} ms")
    print(f"    Max:    {max(total_method_times):.2f} ms")
    print(f"    Median: {statistics.median(total_method_times):.2f} ms")
    print(f"    Mean:   {statistics.mean(total_method_times):.2f} ms")
    print(f"    Std:    {statistics.stdev(total_method_times):.2f} ms")
    print()

    # Distribution buckets
    buckets = {'<1ms': 0, '1-10ms': 0, '10-100ms': 0, '100ms-1s': 0, '>1s': 0}
    for t in total_method_times:
        if t < 1:
            buckets['<1ms'] += 1
        elif t < 10:
            buckets['1-10ms'] += 1
        elif t < 100:
            buckets['10-100ms'] += 1
        elif t < 1000:
            buckets['100ms-1s'] += 1
        else:
            buckets['>1s'] += 1
    print("  Method-time distribution:")
    for label in ['<1ms', '1-10ms', '10-100ms', '100ms-1s', '>1s']:
        n = buckets[label]
        pct = 100.0 * n / len(total_method_times)
        print(f"    {label:12s}  {n:5d}  ({pct:5.1f}%)")
    print()

# Per-file pipeline timing (deduplicated by file)
seen_files = set()
file_rows = []
for r in rows:
    fname = r['file']
    if fname not in seen_files:
        seen_files.add(fname)
        file_rows.append(r)

file_totals = [float(r['file_total_ms']) for r in file_rows]
file_ir = [float(r['file_ir_loading_ms']) for r in file_rows]
file_cg = [float(r['file_call_graph_ms']) for r in file_rows]
file_df = [float(r['file_dataflow_total_ms']) for r in file_rows]
file_ck = [float(r['file_check_total_ms']) for r in file_rows]

print(f"  Unique files: {len(file_rows)}")
print()
print("  Per-file file_total_ms:")
print(f"    Min:    {min(file_totals):.1f} ms")
print(f"    Max:    {max(file_totals):.1f} ms")
print(f"    Median: {statistics.median(file_totals):.1f} ms")
print(f"    Mean:   {statistics.mean(file_totals):.1f} ms")
if len(file_totals) > 1:
    print(f"    Std:    {statistics.stdev(file_totals):.1f} ms")
print()

sum_ir = sum(file_ir)
sum_cg = sum(file_cg)
sum_df = sum(file_df)
sum_ck = sum(file_ck)
sum_total = sum(file_totals)
print("  Pipeline phase breakdown (summed across all files):")
print(f"    IR loading:     {sum_ir:10.1f} ms  ({100*sum_ir/sum_total:5.1f}%)")
print(f"    Call graph:     {sum_cg:10.1f} ms  ({100*sum_cg/sum_total:5.1f}%)")
print(f"    Dataflow:       {sum_df:10.1f} ms  ({100*sum_df/sum_total:5.1f}%)")
print(f"    Check:          {sum_ck:10.1f} ms  ({100*sum_ck/sum_total:5.1f}%)")
print(f"    Total:          {sum_total:10.1f} ms")
print(f"    Unaccounted:    {sum_total - sum_ir - sum_cg - sum_df - sum_ck:10.1f} ms")
print()

# ─── 3. Graph complexity ─────────────────────────────────────────────────────
print("=" * 70)
print("3. GRAPH COMPLEXITY")
print("=" * 70)

nodes = [int(r['graph_nodes']) for r in analyzed]
edges = [int(r['graph_edges']) for r in analyzed]

print(f"\n  graph_nodes (analyzed methods, n={len(nodes)}):")
print(f"    Min:    {min(nodes)}")
print(f"    Max:    {max(nodes)}")
print(f"    Median: {statistics.median(nodes):.1f}")
print(f"    Mean:   {statistics.mean(nodes):.1f}")
print(f"    Std:    {statistics.stdev(nodes):.1f}")

print(f"\n  graph_edges (analyzed methods, n={len(edges)}):")
print(f"    Min:    {min(edges)}")
print(f"    Max:    {max(edges)}")
print(f"    Median: {statistics.median(edges):.1f}")
print(f"    Mean:   {statistics.mean(edges):.1f}")
print(f"    Std:    {statistics.stdev(edges):.1f}")

# Node distribution
print("\n  graph_nodes distribution:")
node_buckets = {'0-5': 0, '6-10': 0, '11-20': 0, '21-50': 0, '51-100': 0, '>100': 0}
for n in nodes:
    if n <= 5:
        node_buckets['0-5'] += 1
    elif n <= 10:
        node_buckets['6-10'] += 1
    elif n <= 20:
        node_buckets['11-20'] += 1
    elif n <= 50:
        node_buckets['21-50'] += 1
    elif n <= 100:
        node_buckets['51-100'] += 1
    else:
        node_buckets['>100'] += 1
for label in ['0-5', '6-10', '11-20', '21-50', '51-100', '>100']:
    cnt = node_buckets[label]
    print(f"    {label:10s}  {cnt:5d}  ({100*cnt/len(nodes):5.1f}%)")

# Correlation: Pearson between graph_nodes and total_method_ms
n_vals = len(analyzed)
mean_nodes = statistics.mean(nodes)
mean_time = statistics.mean(total_method_times)
cov = sum((nodes[i] - mean_nodes) * (total_method_times[i] - mean_time) for i in range(n_vals)) / (n_vals - 1)
std_nodes = statistics.stdev(nodes)
std_time = statistics.stdev(total_method_times)
pearson_r = cov / (std_nodes * std_time) if std_nodes * std_time > 0 else 0
print(f"\n  Pearson correlation (graph_nodes vs total_method_ms): r = {pearson_r:.4f}")

# Also compute with edges
mean_edges = statistics.mean(edges)
cov_e = sum((edges[i] - mean_edges) * (total_method_times[i] - mean_time) for i in range(n_vals)) / (n_vals - 1)
std_edges = statistics.stdev(edges)
pearson_r_e = cov_e / (std_edges * std_time) if std_edges * std_time > 0 else 0
print(f"  Pearson correlation (graph_edges vs total_method_ms): r = {pearson_r_e:.4f}")

# Also total graph size = nodes + edges
total_graph = [nodes[i] + edges[i] for i in range(n_vals)]
mean_tg = statistics.mean(total_graph)
cov_tg = sum((total_graph[i] - mean_tg) * (total_method_times[i] - mean_time) for i in range(n_vals)) / (n_vals - 1)
std_tg = statistics.stdev(total_graph)
pearson_r_tg = cov_tg / (std_tg * std_time) if std_tg * std_time > 0 else 0
print(f"  Pearson correlation (nodes+edges vs total_method_ms):  r = {pearson_r_tg:.4f}")
print()

# ─── 4. False positive analysis ──────────────────────────────────────────────
print("=" * 70)
print("4. FALSE POSITIVE ANALYSIS")
print("=" * 70)

fps = [r for r in rows if r['category'] == 'Tool False Positive']
print(f"\n  Total Tool False Positives: {len(fps)}")

reason_counts = Counter(r['reason'] for r in fps)
print(f"\n  Most common FP reasons:")
for reason, cnt in reason_counts.most_common(20):
    pct = 100.0 * cnt / len(fps)
    print(f"    {cnt:4d} ({pct:5.1f}%)  {reason}")

# "this escapes to global scope" vs others
this_escapes = sum(1 for r in fps if r['reason'] == 'this escapes to global scope')
other = len(fps) - this_escapes
print(f"\n  'this escapes to global scope': {this_escapes} ({100*this_escapes/len(fps):.1f}%)")
print(f"  Other reasons:                  {other} ({100*other/len(fps):.1f}%)")

# Also check for reasons containing "escapes to global scope" more broadly
escapes_global = sum(1 for r in fps if 'escapes to global scope' in r['reason'])
print(f"  Any reason containing 'escapes to global scope': {escapes_global} ({100*escapes_global/len(fps):.1f}%)")
print()

# ─── 5. Files with most false positives ──────────────────────────────────────
print("=" * 70)
print("5. FILES WITH MOST FALSE POSITIVES (Top 10)")
print("=" * 70)

fp_by_file = Counter(r['file'] for r in fps)
print()
for fname, cnt in fp_by_file.most_common(10):
    # also get total methods in that file
    total_in_file = sum(1 for r in rows if r['file'] == fname)
    print(f"  {cnt:4d} FP / {total_in_file:3d} methods  {fname}")
print()

# ─── 6. Files that timed out or were skipped ─────────────────────────────────
print("=" * 70)
print("6. FILES WITH UNUSUALLY HIGH TOTAL TIME (>30s)")
print("=" * 70)

slow_files = [(r['file'], float(r['file_total_ms'])) for r in file_rows if float(r['file_total_ms']) > 30000]
slow_files.sort(key=lambda x: -x[1])
print(f"\n  Files with file_total_ms > 30,000 ms: {len(slow_files)}")
for fname, t in slow_files:
    print(f"    {t:10.1f} ms  ({t/1000:.1f}s)  {fname}")
if not slow_files:
    print("    (none)")

# Also check for files > 10s
slow10 = [(r['file'], float(r['file_total_ms'])) for r in file_rows if float(r['file_total_ms']) > 10000]
slow10.sort(key=lambda x: -x[1])
print(f"\n  Files with file_total_ms > 10,000 ms (10s): {len(slow10)}")
for fname, t in slow10[:20]:
    print(f"    {t:10.1f} ms  ({t/1000:.1f}s)  {fname}")
print()

# ─── 7. Annotation Deficit analysis ──────────────────────────────────────────
print("=" * 70)
print("7. ANNOTATION DEFICIT ANALYSIS")
print("=" * 70)

deficits = [r for r in rows if r['category'] == 'Annotation Deficit']
print(f"\n  Total Annotation Deficit methods: {len(deficits)}")

# For each deficit method, check if the file has ANY other annotated methods
# A method is "annotated" if jdk_annotation is non-empty
file_has_annotation = defaultdict(bool)
for r in rows:
    if r['jdk_annotation'].strip():
        file_has_annotation[r['file']] = True

deficit_in_annotated_file = sum(1 for r in deficits if file_has_annotation[r['file']])
deficit_in_unannotated_file = len(deficits) - deficit_in_annotated_file
print(f"  In files WITH other annotations:    {deficit_in_annotated_file}")
print(f"  In files WITHOUT any annotations:   {deficit_in_unannotated_file}")

# Also check: File Not Annotated
fna = [r for r in rows if r['category'] == 'File Not Annotated']
print(f"\n  Total 'File Not Annotated' methods: {len(fna)}")
fna_files = set(r['file'] for r in fna)
print(f"  Unique files in 'File Not Annotated': {len(fna_files)}")
print()

# ─── 8. Unique files, unique classes ──────────────────────────────────────────
print("=" * 70)
print("8. UNIQUE FILES AND CLASSES")
print("=" * 70)

unique_files = set(r['file'] for r in rows)
unique_classes = set(r['class'] for r in rows)
print(f"\n  Unique files:   {len(unique_files)}")
print(f"  Unique classes: {len(unique_classes)}")
print()

# ─── 9. Jimple statement count statistics ─────────────────────────────────────
print("=" * 70)
print("9. JIMPLE STATEMENT COUNT STATISTICS (analyzed methods)")
print("=" * 70)

jimple = [int(r['jimple_stmts']) for r in analyzed]
print(f"\n  Analyzed methods: {len(jimple)}")
print(f"  Min:    {min(jimple)}")
print(f"  Max:    {max(jimple)}")
print(f"  Median: {statistics.median(jimple):.1f}")
print(f"  Mean:   {statistics.mean(jimple):.1f}")
print(f"  Std:    {statistics.stdev(jimple):.1f}")

# Distribution
print("\n  Jimple statement distribution:")
j_buckets = {'1-5': 0, '6-10': 0, '11-20': 0, '21-50': 0, '51-100': 0, '101-200': 0, '>200': 0}
for j in jimple:
    if j <= 5:
        j_buckets['1-5'] += 1
    elif j <= 10:
        j_buckets['6-10'] += 1
    elif j <= 20:
        j_buckets['11-20'] += 1
    elif j <= 50:
        j_buckets['21-50'] += 1
    elif j <= 100:
        j_buckets['51-100'] += 1
    elif j <= 200:
        j_buckets['101-200'] += 1
    else:
        j_buckets['>200'] += 1
for label in ['1-5', '6-10', '11-20', '21-50', '51-100', '101-200', '>200']:
    cnt = j_buckets[label]
    print(f"    {label:10s}  {cnt:5d}  ({100*cnt/len(jimple):5.1f}%)")

# Correlation between jimple stmts and analysis time
mean_j = statistics.mean(jimple)
cov_j = sum((jimple[i] - mean_j) * (total_method_times[i] - mean_time) for i in range(n_vals)) / (n_vals - 1)
std_j = statistics.stdev(jimple)
pearson_r_j = cov_j / (std_j * std_time) if std_j * std_time > 0 else 0
print(f"\n  Pearson correlation (jimple_stmts vs total_method_ms): r = {pearson_r_j:.4f}")
print()

# ─── EXTRA: Summary table for LaTeX ──────────────────────────────────────────
print("=" * 70)
print("LATEX-READY SUMMARY TABLE")
print("=" * 70)
print()
print("% Category breakdown table")
print("\\begin{table}[t]")
print("\\centering")
print("\\caption{Classification of analysis results.}")
print("\\label{tab:category-breakdown}")
print("\\begin{tabular}{lrr}")
print("\\toprule")
print("Category & Count & \\% \\\\")
print("\\midrule")
for cat in ['Match', 'Tool False Positive', 'Annotation Deficit', 'File Not Annotated', 'Both Side-Effecting', 'Not Analyzed']:
    n = cat_counts.get(cat, 0)
    pct = 100.0 * n / total
    print(f"{cat} & {n} & {pct:.1f}\\% \\\\")
print("\\midrule")
print(f"\\textbf{{Total}} & \\textbf{{{total}}} & \\textbf{{100.0\\%}} \\\\")
print("\\bottomrule")
print("\\end{tabular}")
print("\\end{table}")
print()

print("% Timing table")
print("\\begin{table}[t]")
print("\\centering")
print("\\caption{Per-method analysis time distribution (analyzed methods only).}")
print("\\label{tab:method-time}")
print("\\begin{tabular}{lrr}")
print("\\toprule")
print("Time Range & Count & \\% \\\\")
print("\\midrule")
for label in ['<1ms', '1-10ms', '10-100ms', '100ms-1s', '>1s']:
    n = buckets[label]
    pct = 100.0 * n / len(total_method_times)
    # LaTeX-safe label
    latex_label = label.replace('<', '$<$').replace('>', '$>$')
    print(f"{latex_label} & {n} & {pct:.1f}\\% \\\\")
print("\\bottomrule")
print("\\end{tabular}")
print("\\end{table}")
print()

print("% Pipeline phase breakdown table")
print("\\begin{table}[t]")
print("\\centering")
print("\\caption{Pipeline phase breakdown (summed across all files).}")
print("\\label{tab:pipeline-phases}")
print("\\begin{tabular}{lrr}")
print("\\toprule")
print("Phase & Time (s) & \\% \\\\")
print("\\midrule")
print(f"IR Loading & {sum_ir/1000:.1f} & {100*sum_ir/sum_total:.1f}\\% \\\\")
print(f"Call Graph & {sum_cg/1000:.1f} & {100*sum_cg/sum_total:.1f}\\% \\\\")
print(f"Dataflow & {sum_df/1000:.1f} & {100*sum_df/sum_total:.1f}\\% \\\\")
print(f"Check & {sum_ck/1000:.1f} & {100*sum_ck/sum_total:.1f}\\% \\\\")
print("\\bottomrule")
print("\\end{tabular}")
print("\\end{table}")
print()

print("=" * 70)
print("END OF ANALYSIS")
print("=" * 70)
