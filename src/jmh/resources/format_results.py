#!/usr/bin/env python3
# Disclaimer: This file is mostly AI-generated.
#
# What it does: reads a JMH JSON results file (as produced by the `jmh` Gradle task, e.g.
# build/results/jmh/results.json) and, for each benchmark class found in it, writes a CSV
# with one row per parameter combination, columns for every `Param:*` field plus the
# primary/secondary metrics (e.g. MBperSecond, matchesPerSecond), a `Score error` column with the
# primary metric's JMH standard deviation (scoreError), and a best-effort link to a matching
# flamegraph file under a sibling `flamegraphs/` directory if one exists.
#
# If a sibling `errors.csv` exists (written by BenchmarkState when a benchmark aborts during
# setup, e.g. because an engine cannot compile a pattern set), its messages are added as an
# `Error` column. JMH emits no result entry for an aborted benchmark, so those parameter
# combinations appear as rows contributed solely by that file.
#
# Where/how it's used: invoked by the `formatResults` Gradle task in build.gradle.kts, which
# `./gradlew jmh` always runs right after the benchmarks (via `finalizedBy`), as
# `python3 src/jmh/resources/format_results.py <results.json>`. Requires `python3` with `pandas`
# (see requirements.txt). Can also be run manually on any JMH JSON file:
# `python3 src/jmh/resources/format_results.py <path-to-results.json>`.
import json
import re
import sys
from pathlib import Path
from collections import defaultdict
import pandas as pd


SCORE_ERROR_COLUMN = "Score error"


def normalize_text_token(value):
    return "".join(ch for ch in str(value).lower() if ch.isalnum())


def find_flamegraph_for_entry(entry, flamegraph_files, flamegraph_root):
    if not flamegraph_files:
        return None

    benchmark = entry["benchmark"]
    base_benchmark = benchmark.split(":", 1)[0]
    method_name = base_benchmark.rsplit(".", 1)[-1]
    params = entry.get("params", {}) or {}

    benchmark_tokens = {
        normalize_text_token(base_benchmark),
        normalize_text_token(method_name),
        normalize_text_token(benchmark),
    }
    benchmark_tokens = {token for token in benchmark_tokens if token}

    if not benchmark_tokens:
        return None

    match_tokens = set(benchmark_tokens)
    for param_name, param_value in params.items():
        normalized_name = normalize_text_token(param_name)
        normalized_value = normalize_text_token(param_value)
        if normalized_value:
            match_tokens.add(normalized_value)
        if normalized_name and normalized_value:
            match_tokens.add(f"{normalized_name}{normalized_value}")

    ranked_candidates = []
    for flamegraph_file in flamegraph_files:
        relative_token = normalize_text_token(
            str(flamegraph_file.relative_to(flamegraph_root))
        )
        if not relative_token:
            continue
        if not any(token in relative_token for token in benchmark_tokens):
            continue

        score = sum(len(token) for token in match_tokens if token in relative_token)
        if score == 0:
            continue

        contains_cpu = "cpu" in flamegraph_file.stem.lower()
        is_svg = flamegraph_file.suffix.lower() == ".svg"
        ranked_candidates.append(
            (score, contains_cpu, is_svg, -len(relative_token), flamegraph_file)
        )

    if not ranked_candidates:
        return None

    ranked_candidates.sort(reverse=True)
    best_path = ranked_candidates[0][4].resolve()
    build_dir = flamegraph_root.parents[2].resolve()
    return str(best_path.relative_to(build_dir.parent))


def normalize_param_value(param_name, value):
    """Normalize parameter values for stable grouping/sorting."""
    if value is None:
        raise ValueError(f"Missing {param_name} in results row")

    if param_name == "Param: inputSize":
        return int(float(value))

    if isinstance(value, str):
        lower = value.lower()
        if lower == "true":
            return True
        if lower == "false":
            return False

    return value


def to_float(value):
    if value is None:
        return None
    if isinstance(value, str):
        lower = value.lower()
        if lower == "nan":
            return None
    return float(value)


def sort_value(value):
    if isinstance(value, bool):
        return (0, int(value))
    if isinstance(value, (int, float)):
        return (1, value)
    return (2, str(value))


def load_error_log(errors_path):
    """Read the error log written by BenchmarkState when a benchmark aborts during setup.

    JMH emits no result entry for an aborted benchmark, so these rows have no counterpart in the
    JSON and are added to the table on their own.
    """
    if not errors_path.exists():
        return []

    entries = []
    frame = pd.read_csv(errors_path)
    for _, row in frame.iterrows():
        params = {}
        raw_params = row.get("params")
        if isinstance(raw_params, str):
            for pair in raw_params.split(";"):
                if "=" in pair:
                    name, value = pair.split("=", 1)
                    params[name] = value
        entries.append((str(row["benchmark"]), params, str(row.get("error", ""))))
    return entries


def load_results(json_file):
    print(f"{json_file = }")
    """Load JMH JSON and organize by benchmark + dynamic Param:* columns."""
    results_by_benchmark = defaultdict(lambda: defaultdict(dict))
    param_columns_by_benchmark = defaultdict(list)
    metric_columns_by_benchmark = defaultdict(list)
    seen_param_columns = defaultdict(set)
    seen_metric_columns = defaultdict(set)
    with open(json_file, "r", encoding="utf-8") as handle:
        entries = json.load(handle)

    flamegraph_dir = json_file.parent / "flamegraphs"
    flamegraph_files = []
    if flamegraph_dir.exists() and flamegraph_dir.is_dir():
        flamegraph_files = sorted(
            [
                path
                for path in flamegraph_dir.rglob("*")
                if path.is_file() and path.suffix.lower() in {".svg", ".html"}
            ]
        )

    for entry in entries:
        benchmark = entry["benchmark"]
        base_benchmark = benchmark.split(":", 1)[0]
        row_params = []
        params = entry.get("params", {}) or {}
        for param_name, value in params.items():
            column = f"Param: {param_name}"
            row_params.append((column, normalize_param_value(column, value)))
            if column not in seen_param_columns[base_benchmark]:
                seen_param_columns[base_benchmark].add(column)
                param_columns_by_benchmark[base_benchmark].append(column)
        params_key = tuple(row_params)
        metrics = results_by_benchmark[base_benchmark][params_key]

        primary_metric = entry.get("primaryMetric") or {}
        primary_score = to_float(primary_metric.get("score"))
        primary_column = primary_metric.get("scoreUnit") or "primaryMetric"
        if primary_score is not None:
            metrics[primary_column] = primary_score
            if primary_column not in seen_metric_columns[base_benchmark]:
                seen_metric_columns[base_benchmark].add(primary_column)
                metric_columns_by_benchmark[base_benchmark].append(primary_column)

        # JMH's standard deviation of the primary score, reported as the error of the main metric.
        primary_error = to_float(primary_metric.get("scoreError"))
        if primary_error is not None:
            metrics[SCORE_ERROR_COLUMN] = primary_error
            if SCORE_ERROR_COLUMN not in seen_metric_columns[base_benchmark]:
                seen_metric_columns[base_benchmark].add(SCORE_ERROR_COLUMN)
                metric_columns_by_benchmark[base_benchmark].append(SCORE_ERROR_COLUMN)

        secondary_metrics = entry.get("secondaryMetrics") or {}
        for metric_name, metric in secondary_metrics.items():
            metric_value = to_float((metric or {}).get("score"))
            if metric_value is not None:
                metrics[metric_name] = metric_value
                if metric_name not in seen_metric_columns[base_benchmark]:
                    seen_metric_columns[base_benchmark].add(metric_name)
                    metric_columns_by_benchmark[base_benchmark].append(metric_name)

        flamegraph_path = find_flamegraph_for_entry(
            entry, flamegraph_files, flamegraph_dir
        )
        if flamegraph_path:
            metrics["Flamegraph path"] = flamegraph_path
            if "Flamegraph path" not in seen_metric_columns[base_benchmark]:
                seen_metric_columns[base_benchmark].add("Flamegraph path")
                metric_columns_by_benchmark[base_benchmark].append("Flamegraph path")

    for benchmark, params, message in load_error_log(json_file.parent / "errors.csv"):
        base_benchmark = benchmark.split(":", 1)[0]
        # Order the params the same way the JSON entries did, so a benchmark whose runs all failed
        # still lines up with one where only some did.
        known = [
            column[len("Param: ") :] for column in param_columns_by_benchmark[base_benchmark]
        ]
        ordered_names = [name for name in known if name in params]
        ordered_names += sorted(name for name in params if name not in known)

        row_params = []
        for param_name in ordered_names:
            column = f"Param: {param_name}"
            row_params.append((column, normalize_param_value(column, params[param_name])))
            if column not in seen_param_columns[base_benchmark]:
                seen_param_columns[base_benchmark].add(column)
                param_columns_by_benchmark[base_benchmark].append(column)

        results_by_benchmark[base_benchmark][tuple(row_params)]["Error"] = message
        if "Error" not in seen_metric_columns[base_benchmark]:
            seen_metric_columns[base_benchmark].add("Error")
            metric_columns_by_benchmark[base_benchmark].append("Error")

    return results_by_benchmark, param_columns_by_benchmark, metric_columns_by_benchmark


def create_dataframe(results, param_columns, metric_columns):
    """Create a pandas DataFrame from results."""
    rows = []
    sorted_keys = sorted(
        results.keys(),
        key=lambda params: tuple(sort_value(value) for _, value in params),
    )

    for params in sorted_keys:
        metrics = results[params]
        row = {column.replace("Param: ", ""): value for column, value in params}
        for column in metric_columns:
            if column in ("Flamegraph path", "Error"):
                row[column] = metrics.get(column, "")
            elif column == SCORE_ERROR_COLUMN:
                # Missing (e.g. for failed runs) stays blank rather than a misleading 0.0.
                row[column] = metrics.get(column)
            else:
                row[column] = metrics.get(column, 0.0)
        rows.append(row)

    ordered_param_columns = [column.replace("Param: ", "") for column in param_columns]
    df = pd.DataFrame(rows, columns=ordered_param_columns + metric_columns)
    float_columns = df.select_dtypes(include=["floating"]).columns
    if len(float_columns) > 0:
        df[float_columns] = df[float_columns].round(4)
    return df

def main():
    if len(sys.argv) != 2:
        print("Usage: format_results.py <path-to-jmh-results.json>", file=sys.stderr)
        sys.exit(1)

    json_file = sys.argv[1]
    
    json_path = Path(json_file)
    if not json_path.exists():
        print(f"{json_file=}")
        print(f"Error: File {json_file} not found", file=sys.stderr)
        sys.exit(1)
    
    results_by_benchmark, param_columns_by_benchmark, metric_columns_by_benchmark = load_results(json_path)
    for benchmark_name in sorted(results_by_benchmark.keys()):
        output_file = json_path.parent / f"{benchmark_name}.csv"
        df = create_dataframe(
            results_by_benchmark[benchmark_name],
            param_columns_by_benchmark[benchmark_name],
            metric_columns_by_benchmark[benchmark_name],
        )
        df.to_csv(output_file, index=False)
        print(f"Formatted results written to {output_file}")

if __name__ == "__main__":
    main()
