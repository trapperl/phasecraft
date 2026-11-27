#!/usr/bin/env python3
"""Compare two runs of ./gradlew runPerf (src/perf/README.md), e.g. before and after a change.

    python3 tools/perf_compare.py build/perf-before/results.jsonl build/perf-after/results.jsonl [name-part ...]

For every scenario and variant in both files: the mean tick time of each (the mean of the rounds' means, with the
lowest and highest round), and the change. A change inside the rounds' spread is noise.
"""
import collections
import json
import sys


def load(path):
    runs = collections.defaultdict(list)
    with open(path) as lines:
        for line in lines:
            row = json.loads(line)
            runs[(row["scenario"], row["variant"])].append(row)
    return runs


def spread(rows, key):
    values = [row[key] for row in rows]
    return sum(values) / len(values), min(values), max(values)


def main():
    before, after = load(sys.argv[1]), load(sys.argv[2])
    wanted = sys.argv[3:]
    print("| scenario | variant | before: mean (rounds) | after: mean (rounds) | change | p95 before | p95 after |")
    print("|---|---|---:|---:|---:|---:|---:|")
    for key in before:
        if key not in after or (wanted and not any(part in key[0] for part in wanted)):
            continue
        b, b_low, b_high = spread(before[key], "meanMs")
        a, a_low, a_high = spread(after[key], "meanMs")
        print(f"| {key[0]} | {key[1]} | {b:.3f} ({b_low:.3f} to {b_high:.3f}) | {a:.3f} ({a_low:.3f} to {a_high:.3f}) "
              f"| {100 * (a - b) / b:+.1f}% | {spread(before[key], 'p95Ms')[2]:.2f} | {spread(after[key], 'p95Ms')[2]:.2f} |")


if __name__ == "__main__":
    main()
