# regex-benchmark

A JMH microbenchmark suite comparing regex engines (`java.util.regex`, RE2/J, SafeRE, Joni, Reggie, Vectorscan via
`vectorscan4j` and `hyperscan-java`, dk.brics.automaton, and two Aho-Corasick libraries for literal patterns) on
throughput and compilation cost for common matching workloads: counting matches, grepping lines, and collecting all
match spans.

Additionally, this repository also functions as the replication package for the figures and tables of the paper "Enabling High-Performance Multi-Regex Matching on the JVM: A Java Wrapper Approach Based on Vectorscan" (submitted to ICPE 2027).

## Requirements for running the benchmark

- **Operating system and CPU:** Linux on x86_64 or aarch64 (glibc based).
- **Java 25:** a JDK 25 must be on your `PATH` or set as `JAVA_HOME`.
- **Python 3.12, with `venv` and `pip`** (to install on Ubuntu: `sudo apt install python3 python3-venv python3-pip`).
- **Disk space:** about 250 MB for datasets in `src/jmh/resources/loghub/`.

(Developed and tested on Windows 11 in WSL2 running Ubuntu 24.04.)

## Commands (executed from repo root)

- `./gradlew jmh`: Runs all benchmarks, and collects the benchmark results and runtime errors inside
  `build/results/jmh`. The full benchmark run takes several hours.
- `./gradlew jmh -Pargs="<args>"`: Same as `./gradlew jmh`, except that it overwrites the default JMH arguments (see
  https://github.com/guozheng/jmh-tutorial). For example, to compare the two engines Vectorscan and VectorscanBatched on
  the counting benchmarks, on the Loghub Mac dataset, and with no warmup iterations, call:
  `./gradlew jmh -Pargs="CountBenchmark.loghub -p engine=Vectorscan,VectorscanBatched -p datasetName=Mac -wi 0"`.
- `./gradlew genPaperFigures`: Regenerates exactly the figures of the paper from the results committed in
  `paper/icpe27/results/amd64/` and `paper/icpe27/results/arm64/`.
- `./gradlew genFigures`: Generates figures (PDF) and table bodies (LaTeX) from a full benchmark run,
  into `build/results/figures`. By default it reads the CSV files in `build/results/jmh`; use `-PresultsDir=<dir>` and
  `-PfiguresDir=<dir>` to choose another input or output directory.

## Architecture

Three layers compose each benchmark trial. Each class documents itself in its Javadoc, so read the classes for details.

1. **Engines** (`src/main/java/com/dynatrace/regex/engines/`) wrap one regex library each behind the common
   `RegexEngine` interface (compile a set of patterns, then count matches, count matching lines, or collect all match
   spans in an input). See `RegexEngine`, and e.g. `JavaRegex` or `Vectorscan` for an implementation.

2. **Workloads** (`src/main/java/com/dynatrace/regex/workloads/`) are JMH `@State` classes that generate the patterns
   and inputs for a trial, and choose which engines run on them.

3. **Benchmarks** (`src/jmh/java/com/dynatrace/regex/benchmarks/`) contain the `@Benchmark` methods, one class per
   operation and one method per workload. See e.g. `CountBenchmark` for matching throughput and `CompilationBenchmark`
   for compilation cost.

## Extending the benchmark suite

To add a new engine: implement a new `RegexEngine` in `engines/` and add the class name to the `engine` `@Param({...})`
lists of the workload bases (`LiteralEngineWorkload`, `RegexEngineWorkload`) where it is valid.

To add a new workload: implement a new class that extends either `LiteralEngineWorkload` or `RegexEngineWorkload`, set `patterns` and `inputs` in
`setupWorkload()`, then add a corresponding `@Benchmark` method to each benchmark class in `benchmarks/` that you think
this workload is relevant for.

# License

This project is licensed under the Apache License 2.0 (see `LICENSE` and `NOTICE`). Third-party libraries and the
Loghub datasets are listed in `THIRD_PARTY_NOTICES.md`.
