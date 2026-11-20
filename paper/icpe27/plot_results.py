#!/usr/bin/env python3
"""Generate the evaluation figures and tables of the vectorscan4j paper (ICPE 2027).

AI-generated: written by an AI coding agent (Claude Code) with little manual editing.

What it does: reads the CSV files of the benchmark runs used in the paper (`results/<run>/`, converted from the
JMH JSON output, one CSV per benchmark class and workload) and writes the figures (PDF) and table bodies (LaTeX) that
the paper includes. Given the runs on both architectures, it also writes the ARM64 vs. AMD64 comparison
(`arch-comparison.pdf`, `arch-table.tex`).

How it is used:
    python3 paper/icpe27/plot_results.py [--data <run-dir>] [--compare <arm64-run-dir>] [--out <dir>]
`--data` defaults to the AMD64 run in `results/`, `--compare` takes the ARM64 run, `--out` defaults to
`paper/icpe27/figures/`. Pass the paper's `figures/` directory as `--out` to update the paper. The Gradle tasks
`genFigures` and `genPaperFigures` run it.
"""
import argparse
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
DEFAULT_DATA = HERE / "results" / "amd64"
OUT = HERE / "figures"
PREFIX = "com.dynatrace.regex.benchmarks."

# One entry per engine, keyed by the class name of the engine, in the fixed order used by every figure and table.
# The value is (display name, color, marker, supports regexes as a bool). The colors are categorical, assigned per engine in
# fixed order (never cycled), and the marker is a secondary encoding, so that the figures stay readable in grayscale
# print and for color-vision deficiencies. The engines that do not support regexes only match literal patterns.
ENGINES = {
    "Vectorscan": ("vectorscan4j", "#2a78d6", "o", True),
    "HyperscanJava": ("hyperscan-java", "#eb6834", "s", True),
    "JavaRegex": ("java.util.regex", "#1baf7a", "^", True),
    "Re2": ("RE2J", "#eda100", "v", True),
    "SafeRe": ("SafeRE", "#e87ba4", "D", True),
    "Joni": ("Joni", "#00a5b5", "h", True),
    "Reggie": ("Reggie", "#008300", "P", True),
    "DkBricsAutomaton": ("dk.brics.automaton", "#6250d6", "X", True),
    "AhoCorasick": ("Aho-Corasick", "#7a7a7a", "p", False),
    "AhoCorasick2": ("ACDAT", "#e34948", "*", False),
}


def display_name(engine):
    return ENGINES[engine][0]


def color_of(engine):
    return ENGINES[engine][1]


def marker_of(engine):
    return ENGINES[engine][2]


def supports_regex(engine):
    return ENGINES[engine][3]


REGEX_ENGINES = [e for e in ENGINES if supports_regex(e)]
LITERAL_ENGINES = [e for e in ENGINES if not supports_regex(e)]

plt.rcParams.update({
    "font.size": 7,
    "axes.titlesize": 7,
    "axes.labelsize": 7,
    "xtick.labelsize": 6.5,
    "ytick.labelsize": 6.5,
    "legend.fontsize": 6.5,
    "axes.spines.top": False,
    "axes.spines.right": False,
    "axes.edgecolor": "#888888",
    "axes.linewidth": 0.6,
    "xtick.color": "#444444",
    "ytick.color": "#444444",
    "xtick.major.width": 0.6,
    "ytick.major.width": 0.6,
    "grid.color": "#e3e3e3",
    "grid.linewidth": 0.5,
    "lines.linewidth": 1.2,
    "lines.markersize": 3.5,
    # embed TrueType instead of Type 3 fonts, as required by the ACM production checks
    "pdf.fonttype": 42,
    "ps.fonttype": 42,
})


# Number of templates (= patterns) per Loghub-2.0 dataset.
TEMPLATES = {
    "Apache": 29, "HPC": 74, "Hadoop": 236, "HealthApp": 156, "Linux": 338,
    "Mac": 626, "OpenSSH": 38, "OpenStack": 48, "Proxifier": 11, "Zookeeper": 89,
}


def load(data, benchmark, workload):
    """Reads the CSV of one (benchmark class, workload) pair."""
    path = Path(data) / f"{PREFIX}{benchmark}Benchmark.{workload}.csv"
    if not path.is_file():
        raise SystemExit(
            f"missing {path.name} in {data}: the figures need the results of a full benchmark run "
            f"(Count, Compilation: trivial, singleRegex, randomLiterals, randomRegexes, loghub)"
        )
    d = pd.read_csv(path)
    if "Error" in d:
        # a configuration that failed has no measurement (the formatted results contain zeros for it)
        failed = d["Error"].notna()
        metrics = [c for c in d.columns if c in METRIC_COLUMNS and c != "Error"]
        d.loc[failed, metrics] = np.nan
    return d


def legend_handles(engines):
    return [
        plt.Line2D([], [], color=color_of(e), marker=marker_of(e), linewidth=1.2, label=display_name(e)) for e in engines
    ]


# ---------------------------------------------------------------------------------------------------------------
# Figure: Loghub count throughput per dataset
# ---------------------------------------------------------------------------------------------------------------
def loghub_counts(data):
    d = load(data, "Count", "loghub")
    tput = d.pivot(index="datasetName", columns="engine", values="MBperSecond")
    templates = TEMPLATES
    datasets = sorted(tput.index, key=lambda ds: templates[ds])
    fig, ax = plt.subplots(figsize=(7.0, 2.0))
    engines = [e for e in REGEX_ENGINES if e in tput.columns]  # an engine missing from the run is left out
    n = len(engines)
    width = 0.8 / n
    x = np.arange(len(datasets))
    for i, e in enumerate(engines):
        ax.bar(x - 0.4 + (i + 0.5) * width, tput.loc[datasets, e], width * 0.85, color=color_of(e), label=display_name(e),
               zorder=3)
    ax.set_yscale("log")
    ax.set_ylabel("Throughput [MB/s]")
    ax.set_xticks(x, [f"{ds}\n({templates[ds]})" for ds in datasets])
    ax.set_xlim(-0.5, len(datasets) - 0.5)
    ax.grid(axis="y", which="major", zorder=0)
    ax.legend(ncol=n, loc="lower center", bbox_to_anchor=(0.5, 1.0), frameon=False, handlelength=1.0,
              columnspacing=1.2)
    fig.tight_layout(pad=0.2)
    return fig


# ---------------------------------------------------------------------------------------------------------------
# Figure: throughput vs. number of patterns (random literals and random regexes)
# ---------------------------------------------------------------------------------------------------------------
def scaling(data):
    lit = load(data, "Count", "randomLiterals")
    lit = lit[lit.matchDensity == 0.001]
    rx = load(data, "Count", "randomRegexes")
    panels = [
        ("(a) Random literals (match density 0.1\\%)".replace("\\%", "%"), lit,
         ["Vectorscan", "HyperscanJava", "JavaRegex", "SafeRe", "DkBricsAutomaton"] + LITERAL_ENGINES),
        ("(b) Random regexes", rx, REGEX_ENGINES),
    ]
    fig, axes = plt.subplots(1, 2, figsize=(7.0, 2.2), sharey=True)
    for ax, (title, d, engines) in zip(axes, panels):
        t = d.pivot(index="nPatterns", columns="engine", values="MBperSecond").sort_index()
        for e in engines:
            if e not in t:
                continue
            s = t[e].dropna()
            ax.plot(s.index, s.values, color=color_of(e), marker=marker_of(e), label=display_name(e), zorder=3)
        ax.set_xscale("log", base=2)
        ax.set_yscale("log")
        ax.set_xticks(t.index, [str(v) for v in t.index])
        ax.minorticks_off()
        ax.set_xlabel("Number of patterns")
        ax.set_title(title, loc="left")
        ax.grid(which="major", zorder=0)
    axes[0].set_ylabel("Throughput [MB/s]")
    present = set(lit.engine) | set(rx.engine)
    all_engines = [e for e in ENGINES if e in present]
    rows = 2 if len(all_engines) > 7 else 1  # the legend wraps onto two rows when there are many engines
    fig.legend(handles=legend_handles(all_engines), ncol=-(-len(all_engines) // rows), loc="upper center",
               frameon=False, handlelength=1.6, columnspacing=1.2)
    fig.tight_layout(pad=0.2, rect=(0, 0, 1, 0.86 if rows == 1 else 0.8))
    return fig


# ---------------------------------------------------------------------------------------------------------------
# Figure: cost of match reporting (trivial workload, single pattern "a")
# ---------------------------------------------------------------------------------------------------------------
def match_density(data):
    d = load(data, "Count", "trivial")
    t = d.pivot(index="matchDensity", columns="engine", values="MBperSecond").sort_index()
    x = np.arange(len(t.index))
    series = [
        ("VectorscanBatched", "vectorscan4j (batched handler)", color_of("Vectorscan"), "o", "--"),
        ("Vectorscan", "vectorscan4j (Java upcall)", color_of("Vectorscan"), "o", "-"),
        ("HyperscanJava", "hyperscan-java", color_of("HyperscanJava"), "s", "-"),
        ("JavaRegex", "java.util.regex", color_of("JavaRegex"), "^", "-"),
    ]
    fig, ax = plt.subplots(figsize=(3.33, 2.0))
    for e, label, color, marker, ls in series:
        ax.plot(x, t[e].values, color=color, marker=marker, linestyle=ls, label=label, zorder=3)
    ax.set_yscale("log")
    ax.set_xticks(x, ["0", "0.1%", "1%", "10%"])
    ax.set_xlabel("Match density (matches per input byte)")
    ax.set_ylabel("Throughput [MB/s]")
    ax.grid(which="major", zorder=0)
    ax.legend(frameon=False, loc="lower left", handlelength=2.2)
    fig.tight_layout(pad=0.2)
    return fig


# ---------------------------------------------------------------------------------------------------------------
# Table: single-regex count throughput
# ---------------------------------------------------------------------------------------------------------------
SINGLE_GROUPS = [
    ("Literals", ["LONG_LITERAL", "CASE_INSENSITIVE", "WORD_BOUNDARY"]),
    ("Real-world tokens", ["EMAIL", "IPV4", "ISO_DATE", "ISO_TIMESTAMP", "UUID", "URL"]),
    ("Classes and repetition", ["COUNTED_CLASS", "COUNTED_DIGITS", "COMMON_FIRST_CHAR", "DENSE_WORDS",
                                "ANCHORED_DIGITS"]),
    ("Wildcards", ["LEADING_WILDCARD", "MIDDLE_WILDCARD"]),
    ("Backtracking blow-up", ["NESTED_QUANTIFIERS", "OVERLAPPING_ALTERNATION", "MANY_REPEATS"]),
    ("Non-regular", ["BACKREFERENCE", "LOOKAHEAD", "ATOMIC_GROUP"]),
]
SINGLE_COLUMNS = ["JavaRegex", "Re2", "SafeRe", "Joni", "Reggie", "DkBricsAutomaton", "HyperscanJava", "Vectorscan",
                  "VectorscanBatched"]
ALL_MATCHES_ENGINES = {"HyperscanJava", "Vectorscan", "VectorscanBatched"}

# The regex of every single-regex workload, copied from SingleRegexWorkload.RegexAndInput at the benchmarked commit
# (see results/<run>/input/git-log.txt).
SINGLE_REGEXES = {
    "LONG_LITERAL": "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789",
    "CASE_INSENSITIVE": r"(?i)error",
    "WORD_BOUNDARY": r"\bcat\b",
    "EMAIL": r"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}",
    "IPV4": r"\b(?:\d{1,3}\.){3}\d{1,3}\b",
    "ISO_DATE": r"\d{4}-\d{2}-\d{2}",
    "ISO_TIMESTAMP": r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})?",
    "UUID": r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
    "URL": r"https?://[\w./-]+",
    "COUNTED_CLASS": r"x{1,50}y",
    "COUNTED_DIGITS": r"\d{3,10}",
    "COMMON_FIRST_CHAR": r"[a-z]z\d+",
    "DENSE_WORDS": r"\w+",
    "ANCHORED_DIGITS": r"^\d+$",
    "LEADING_WILDCARD": r".*needle",
    "MIDDLE_WILDCARD": r"start.*end",
    "NESTED_QUANTIFIERS": r"(a+)+b",
    "OVERLAPPING_ALTERNATION": r"(a|aa)*c",
    "MANY_REPEATS": "a?" * 10 + "a" * 10,
    "BACKREFERENCE": r"(\w+) \1",
    "LOOKAHEAD": r"foo(?=bar)",
    "ATOMIC_GROUP": r"(?>a+)b",
}

LATEX_SPECIAL = {
    "\\": r"\textbackslash{}", "{": r"\{", "}": r"\}", "_": r"\_", "^": r"\textasciicircum{}", "%": r"\%",
    "#": r"\#", "&": r"\&", "$": r"\$", "~": r"\textasciitilde{}",
}


def latex_regex(regex):
    """Typesets a regex verbatim in typewriter font, allowing a line break after every character."""
    return r"\texttt{" + r"\allowbreak{}".join(LATEX_SPECIAL.get(c, c) for c in regex) + "}"


# Zebra striping, as in the engine comparison table of the paper: every other row (starting with the first) is gray.
STRIPE = "\\rowcolor{gray!20}"


def stripe(i):
    return STRIPE if i % 2 == 0 else ""


def fmt(v):
    if v >= 100:
        return f"{v:,.0f}".replace(",", "\\,")
    if v >= 10:
        return f"{v:.0f}"
    return f"{v:.1f}"


def single_regex_table(data):
    """Returns the rows of Table 2 as LaTeX."""
    d = load(data, "Count", "singleRegex")
    d["matchesPerOp"] = d["matchesPerSecond"] / d["ops/s"]
    # an engine missing from the run gets "--" cells
    tput = d.pivot(index="workload", columns="engine", values="MBperSecond").reindex(columns=SINGLE_COLUMNS)
    mpo = d.pivot(index="workload", columns="engine", values="matchesPerOp").reindex(columns=SINGLE_COLUMNS)
    lines = []
    for group, workloads in SINGLE_GROUPS:
        # group headings on a dark background; inside a group, every second row is gray
        lines.append(f"\\rowcolor{{gray!80}} \\multicolumn{{{len(SINGLE_COLUMNS) + 2}}}{{l}}{{\\textbf{{{group}}}}} \\\\")
        for row, w in enumerate(workloads):
            ref = mpo.loc[w, "JavaRegex"]
            cells, valid = [], {}
            for e in SINGLE_COLUMNS:
                v = tput.loc[w, e]
                if np.isnan(v):
                    cells.append("--")
                    continue
                # Engines with leftmost-first semantics must find as many matches as java.util.regex. A different
                # count means the engine interpreted the pattern differently (e.g. dk.brics.automaton has no \d).
                wrong = e not in ALL_MATCHES_ENGINES and not np.isnan(ref) and abs(mpo.loc[w, e] - ref) > 0.01 * max(ref, 1)
                cells.append(fmt(v) + ("$^\\dagger$" if wrong else ""))
                if not wrong:
                    valid[e] = v
            if valid:
                best = max(valid, key=valid.get)
                i = SINGLE_COLUMNS.index(best)
                cells[i] = "\\textbf{" + cells[i] + "}"
            name = w.replace("_", "\\_")
            lines.append(f"{stripe(row + 1)}\\quad\\texttt{{{name}}} & {latex_regex(SINGLE_REGEXES[w])} & "
                         + " & ".join(cells) + " \\\\")
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------------------------------------------------------
# Table: compilation time and database size for the Loghub pattern sets
# ---------------------------------------------------------------------------------------------------------------
COMPILE_COLUMNS = ["JavaRegex", "Re2", "SafeRe", "Joni", "Reggie", "DkBricsAutomaton", "HyperscanJava", "Vectorscan"]


def compile_table(data):
    """Returns the rows of Table 3 as LaTeX."""
    d = load(data, "Compilation", "loghub")
    ms = d.pivot(index="datasetName", columns="engine", values="s/op").reindex(columns=COMPILE_COLUMNS) * 1000
    db = d.pivot(index="datasetName", columns="engine", values="nativeMemoryUsage")["Vectorscan"] / 1024
    templates = TEMPLATES
    lines = []
    for i, ds in enumerate(sorted(ms.index, key=lambda x: templates[x])):
        cells = [fmt(ms.loc[ds, e]) for e in COMPILE_COLUMNS]
        lines.append(f"{stripe(i)}{ds} & {templates[ds]} & " + " & ".join(cells) + f" & {db[ds]:.0f} \\\\")
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------------------------------------------------------
# Figure and table: ARM64 vs. AMD64 (Count throughput of the same configuration on both architectures)
# ---------------------------------------------------------------------------------------------------------------
METRIC_COLUMNS = {"ops/s", "Score error", "MBperSecond", "gc.alloc.rate", "gc.alloc.rate.norm", "gc.count",
                  "matchesPerSecond", "measuredMatchDensity", "totalMB", "totalMatches", "gc.time", "Error",
                  "flamegraph", "s/op", "heapUsage", "nativeMemoryUsage"}
ARCH_WORKLOADS = [("loghub", "Loghub"), ("singleRegex", "Single regex"), ("randomLiterals", "Random literals"),
                  ("randomRegexes", "Random regexes")]
ARCH_ENGINES = list(ENGINES)


def arch_ratios(data, other):
    """Returns one row per configuration that ran on both architectures, with the throughput ratio other / data."""
    frames = []
    for workload, label in ARCH_WORKLOADS:
        a = load(data, "Count", workload)
        b = load(other, "Count", workload)
        keys = [c for c in a.columns if c not in METRIC_COLUMNS]
        m = a[keys + ["MBperSecond"]].merge(b[keys + ["MBperSecond"]], on=keys, suffixes=("_a", "_b"))
        m = m[(m.MBperSecond_a > 0) & (m.MBperSecond_b > 0)]
        m["ratio"] = m.MBperSecond_b / m.MBperSecond_a
        m["workload"] = label
        frames.append(m[["engine", "workload", "ratio"]])
    return pd.concat(frames, ignore_index=True)


def arch_comparison(data, other):
    """Per engine, the throughput on ARM64 relative to AMD64 for every configuration of the Count benchmarks."""
    r = arch_ratios(data, other)
    engines = [e for e in ARCH_ENGINES if e in set(r.engine)]
    fig, ax = plt.subplots(figsize=(7.0, 2.6))
    rng = np.random.default_rng(0)
    for row, e in enumerate(engines):
        v = r[r.engine == e].ratio.values
        y = row + rng.uniform(-0.28, 0.28, len(v))
        ax.scatter(v, y, s=7, color=color_of(e), alpha=0.55, linewidths=0, zorder=3)
        ax.plot([np.exp(np.log(v).mean())] * 2, [row - 0.4, row + 0.4], color="black", linewidth=1.6, zorder=4)
    ax.axvline(1.0, color="gray", linewidth=0.8, linestyle="--", zorder=2)
    ax.set_xscale("log", base=2)
    ax.set_yticks(range(len(engines)), [display_name(e) for e in engines])
    ax.set_ylim(len(engines) - 0.5, -0.5)
    ax.set_xlabel("Throughput ARM64 / AMD64 (>1: ARM64 faster; black bar: geometric mean)")
    ax.grid(axis="x", which="major", zorder=0)
    fig.tight_layout(pad=0.2)
    return fig


def arch_table(data, other):
    """Returns the rows of the architecture table as LaTeX: geometric mean of ARM64 / AMD64 per engine and workload."""
    r = arch_ratios(data, other)
    lines = []
    for i, e in enumerate(ARCH_ENGINES):
        sub = r[r.engine == e]
        cells = []
        for _, label in ARCH_WORKLOADS:
            v = sub[sub.workload == label].ratio.values
            cells.append(f"{np.exp(np.log(v).mean()):.2f}" if len(v) else "--")
        v = sub.ratio.values
        overall = f"{np.exp(np.log(v).mean()):.2f}" if len(v) else "--"
        lines.append(f"{stripe(i)}{display_name(e)} & " + " & ".join(cells) + f" & {overall} \\\\")
    return "\n".join(lines) + "\n"


FIGURES = {"loghub-count": loghub_counts, "scaling-count": scaling, "match-density": match_density}
TABLES = {"single-regex-table": single_regex_table, "compile-table": compile_table}
# Only written when the results of a second architecture are given.
COMPARISON_FIGURES = {"arch-comparison": arch_comparison}
COMPARISON_TABLES = {"arch-table": arch_table}


def write_all(data=DEFAULT_DATA, out=OUT, other=None):
    """Writes all figures (PDF) and table bodies (LaTeX) used by the paper into `out`.

    `data` holds the primary run (AMD64 in the paper). With `other` (the same run on another architecture), the
    architecture comparison is written as well, showing `other` relative to `data`.
    """
    Path(out).mkdir(parents=True, exist_ok=True)
    for name, make in FIGURES.items():
        fig = make(data)
        # no creation date, so that regenerating unchanged figures does not change the files
        fig.savefig(Path(out) / f"{name}.pdf", metadata={"CreationDate": None})
        plt.close(fig)
    for name, make in TABLES.items():
        (Path(out) / f"{name}.tex").write_text(make(data))
    if other is not None:
        for name, make in COMPARISON_FIGURES.items():
            fig = make(data, other)
            fig.savefig(Path(out) / f"{name}.pdf", metadata={"CreationDate": None})
            plt.close(fig)
        for name, make in COMPARISON_TABLES.items():
            (Path(out) / f"{name}.tex").write_text(make(data, other))


if __name__ == "__main__":
    import matplotlib

    matplotlib.use("Agg")
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--data", type=Path, default=DEFAULT_DATA, help="directory with the run's CSV files")
    parser.add_argument("--out", type=Path, default=OUT, help="directory to write the figures and tables to")
    parser.add_argument("--compare", type=Path, default=None,
                        help="directory with the CSV files of the same run on another architecture (ARM64 in the "
                             "paper), to also write the architecture comparison")
    args = parser.parse_args()
    write_all(args.data, args.out, args.compare)
    print("written to", args.out)
