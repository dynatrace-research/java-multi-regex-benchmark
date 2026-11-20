/*
 Copyright 2026 JKU/Dynatrace Co-Innovation Lab

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
*/
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.runner.BenchmarkList;
import org.openjdk.jmh.runner.BenchmarkListEntry;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.format.OutputFormat;
import org.openjdk.jmh.runner.format.OutputFormatFactory;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.VerboseMode;
import org.openjdk.jmh.util.Optional;
import org.openjdk.jol.vm.VM;

public class JmhSmokeTest {
    private static final String ENGINE_PARAM = "engine";

    /** Restricts the run to these engines (e.g. {@code List.of("Re2", "Re2Multi")}). Empty means all engines. */
    private static final List<String> ONLY_ENGINES = List.of("Vectorscan");

    /** Budget for one engine/benchmark combination, covering JMH's trial @Setup (where pattern
     *  compilation happens) as well as the measured invocation. */
    private static final long RUN_TIMEOUT_SECONDS = 5;

    /** Params exercised across every declared value, each value becoming its own matrix column.
     *  Every other @Param is pinned to its first declared value, otherwise a single engine would expand
     *  to the full param cross-product (hundreds of trials). */
    private static final Set<String> EXPANDED_PARAMS = Set.of("datasetName");

    /** One matrix column: a benchmark method plus a concrete set of @Param values. A benchmark that
     *  declares an expanded param contributes one Bench per value of that param. */
    private record Bench(
            String key, String username, String label, Map<String, String> pinned, Set<String> declaredEngines) {}

    private enum Status {
        OK,
        FAILED,
        TIMED_OUT
    }

    private record Outcome(String engine, Bench bench, boolean declared, Status status, String detail) {}

    /**
     * Runs every engine against every benchmark and records which combinations work. Both the benchmark
     * list and the engine list are discovered from JMH's own metadata, so adding a @Benchmark or a new
     * engine to an @Param list shows up here automatically.
     *
     * <p>The matrix is printed as it fills in, and the full transcript is written to
     * {@code build/reports/engine-compat.txt}. Individual failures are reported rather than failing the
     * test; only a wholesale harness breakage (nothing succeeds anywhere) fails.
     */
    @Test
    void reportsEngineWorkloadCompatibility() throws Exception {
        Set<BenchmarkListEntry> entries = BenchmarkList.defaultList().getAll(silentFormat(), List.of());
        List<Bench> benches = discoverBenchmarks(entries);
        List<String> engines = selectEngines(discoverEngines(benches));
        assertTrue(!benches.isEmpty(), "JMH discovered no engine-parameterized benchmarks on the test classpath.");

        Path reportDir = Path.of("build", "reports");
        Files.createDirectories(reportDir);
        Path jmhLog = reportDir.resolve("engine-compat-jmh.log");
        Path transcriptFile = reportDir.resolve("engine-compat.txt");
        Path csv = reportDir.resolve("engine-compat.csv");

        initialiseJolQuietly();

        Report report = new Report();
        List<Outcome> outcomes = new ArrayList<>();
        int labelWidth =
                benches.stream().mapToInt(b -> b.label().length()).max().orElse(9);
        int colWidth = engines.stream().mapToInt(String::length).max().orElse(6);
        printPreamble(report, engines, labelWidth, colWidth);

        try {
            for (Bench bench : benches) {
                report.text(padRight(bench.label(), labelWidth));
                for (String engine : engines) {
                    Outcome outcome = runWithTimeout(engine, bench, jmhLog);
                    outcomes.add(outcome);
                    report.text(" " + padRight(cell(outcome), colWidth));
                }
                report.line("");
            }
        } finally {
            printFailures(report, outcomes);
            printSummary(report, benches, outcomes, transcriptFile, csv);
            report.writeTo(transcriptFile);
            writeCsv(outcomes, csv);
        }

        long ok = outcomes.stream().filter(o -> o.status() == Status.OK).count();
        assertTrue(ok > 0, "No engine/benchmark combination succeeded - the JMH harness itself is broken.");
    }

    /**
     * JOL prints environment warnings (failed self-attach, Serviceability Agent) to System.out the first
     * time its VM class initialises. That would otherwise happen inside the first benchmark's setup,
     * in the middle of the table, so it is triggered here with both streams muted.
     */
    private static void initialiseJolQuietly() {
        PrintStream out = System.out;
        PrintStream err = System.err;
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        try {
            VM.current();
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    private static OutputFormat silentFormat() {
        return OutputFormatFactory.createFormatInstance(
                new PrintStream(OutputStream.nullOutputStream()), VerboseMode.SILENT);
    }

    private static List<Bench> discoverBenchmarks(Set<BenchmarkListEntry> entries) {
        List<Bench> benches = new ArrayList<>();
        for (BenchmarkListEntry entry : entries) {
            Optional<Map<String, String[]>> declared = entry.getParams();
            if (!declared.hasValue() || !declared.get().containsKey(ENGINE_PARAM)) {
                continue; // not engine-parameterized, so it has no place in an engine matrix
            }
            Map<String, String[]> params = declared.get();
            Map<String, String> pinned = new LinkedHashMap<>();
            List<Map.Entry<String, String[]>> expandable = new ArrayList<>();
            for (var param : new TreeMap<>(params).entrySet()) {
                if (param.getKey().equals(ENGINE_PARAM) || param.getValue().length == 0) {
                    continue;
                }
                if (EXPANDED_PARAMS.contains(param.getKey())) {
                    expandable.add(param);
                } else {
                    pinned.put(param.getKey(), param.getValue()[0]);
                }
            }
            Set<String> declaredEngines = Set.copyOf(List.of(params.get(ENGINE_PARAM)));
            String baseLabel = label(entry.getUsername());
            for (Map<String, String> expansion : expansions(expandable)) {
                Map<String, String> values = new LinkedHashMap<>(pinned);
                values.putAll(expansion);
                String suffix = expansion.isEmpty() ? "" : "[" + String.join(",", expansion.values()) + "]";
                benches.add(new Bench(
                        entry.getUsername() + suffix,
                        entry.getUsername(),
                        baseLabel + suffix,
                        values,
                        declaredEngines));
            }
        }
        benches.sort(Comparator.comparing(Bench::key));
        return benches;
    }

    /** Cartesian product over the expanded params; a single empty map when nothing is expanded. */
    private static List<Map<String, String>> expansions(List<Map.Entry<String, String[]>> expandable) {
        List<Map<String, String>> result = new ArrayList<>();
        result.add(new LinkedHashMap<>());
        for (var param : expandable) {
            List<Map<String, String>> next = new ArrayList<>();
            for (Map<String, String> partial : result) {
                for (String value : param.getValue()) {
                    Map<String, String> combined = new LinkedHashMap<>(partial);
                    combined.put(param.getKey(), value);
                    next.add(combined);
                }
            }
            result = next;
        }
        return result;
    }

    private static List<String> selectEngines(List<String> discovered) {
        if (ONLY_ENGINES.isEmpty()) {
            return discovered;
        }
        List<String> unknown =
                ONLY_ENGINES.stream().filter(e -> !discovered.contains(e)).toList();
        assertTrue(
                unknown.isEmpty(), "ONLY_ENGINES names unknown engines: " + unknown + " (known: " + discovered + ")");
        return discovered.stream().filter(ONLY_ENGINES::contains).toList();
    }

    private static List<String> discoverEngines(List<Bench> benches) {
        TreeSet<String> engines = new TreeSet<>();
        benches.forEach(b -> engines.addAll(b.declaredEngines()));
        return List.copyOf(engines);
    }

    /** com.dynatrace.regex.benchmarks.CountBenchmark.loghub -> Count.loghub */
    private static String label(String username) {
        String[] parts = username.split("\\.");
        if (parts.length < 2) {
            return username;
        }
        return parts[parts.length - 2].replace("Benchmark", "") + "." + parts[parts.length - 1];
    }

    private static Outcome run(String engine, Bench bench, Path jmhLog) {
        OptionsBuilder builder = new OptionsBuilder();
        builder.include(Pattern.quote(bench.username()) + "$")
                .mode(Mode.SingleShotTime) // one invocation is enough to prove the combination runs
                .warmupIterations(0)
                .measurementIterations(1)
                .forks(0)
                .param(ENGINE_PARAM, engine)
                .verbosity(VerboseMode.SILENT)
                .shouldFailOnError(true)
                .output(jmhLog.toString());
        bench.pinned().forEach(builder::param);
        boolean declared = bench.declaredEngines().contains(engine);
        try {
            new Runner(builder.build()).run();
            return new Outcome(engine, bench, declared, Status.OK, "");
        } catch (Throwable t) {
            return new Outcome(engine, bench, declared, Status.FAILED, describe(t));
        }
    }

    /**
     * Runs one combination on a daemon thread so a wedged engine cannot stall the whole report. The
     * budget spans the entire {@link Runner#run()} call, which includes JMH's trial @Setup.
     *
     * <p>Note that a timed-out run is only interrupted, not killed: an engine stuck in a tight
     * compile loop keeps burning CPU in the background until the JVM exits.
     */
    private static Outcome runWithTimeout(String engine, Bench bench, Path jmhLog) {
        ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "engine-compat");
            thread.setDaemon(true);
            return thread;
        });
        boolean declared = bench.declaredEngines().contains(engine);
        Future<Outcome> pending = executor.submit(() -> run(engine, bench, jmhLog));
        try {
            return pending.get(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            return new Outcome(engine, bench, declared, Status.TIMED_OUT, "exceeded " + RUN_TIMEOUT_SECONDS + "s");
        } catch (Exception e) {
            return new Outcome(engine, bench, declared, Status.FAILED, describe(e));
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * JMH attaches a benchmark's real exceptions as suppressed ones and leaves getCause() null, so both
     * are walked. The first frame in project code is kept so the failing line can be found.
     */
    private static String describe(Throwable t) {
        List<Throwable> children = new ArrayList<>(List.of(t.getSuppressed()));
        if (t.getCause() != null && t.getCause() != t) {
            children.add(t.getCause());
        }
        String self = oneLine(t);
        if (children.isEmpty()) {
            return self;
        }
        String inner = children.stream().map(JmhSmokeTest::describe).distinct().collect(Collectors.joining(" | "));
        return self + " [" + inner + "]";
    }

    private static String oneLine(Throwable t) {
        String message = t.getMessage();
        String text = t.getClass().getSimpleName()
                + (message == null || message.isBlank()
                        ? ""
                        : ": " + message.lines().findFirst().orElse("").trim());
        return Arrays.stream(t.getStackTrace())
                .filter(frame -> frame.getClassName().startsWith("com.dynatrace"))
                .findFirst()
                .map(frame -> text + " @ " + frame.getFileName() + ":" + frame.getLineNumber())
                .orElse(text);
    }

    private static String cell(Outcome o) {
        return switch (o.status()) {
            case OK -> o.declared() ? "." : "o";
            case TIMED_OUT -> o.declared() ? "T" : "t";
            case FAILED -> o.declared() ? "X" : "-";
        };
    }

    private static void printPreamble(Report report, List<String> engines, int labelWidth, int colWidth) {
        report.line("legend: . = works (listed in @Param)    X = BROKEN (listed in @Param but fails)");
        report.line("        o = works (not listed - could be added)    - = fails (not listed, expected)");
        report.line("        T = timed out (listed in @Param)           t = timed out (not listed)");
        report.line("");
        report.text(padRight("benchmark", labelWidth));
        for (String engine : engines) {
            report.text(" " + padRight(engine, colWidth));
        }
        report.line("");
        report.line("-".repeat(labelWidth + (colWidth + 1) * engines.size()));
    }

    private static void printFailures(Report report, List<Outcome> outcomes) {
        List<Outcome> failing =
                outcomes.stream().filter(o -> o.status() != Status.OK).toList();
        if (failing.isEmpty()) {
            return;
        }
        report.line("");
        report.line("failures (%d):".formatted(failing.size()));
        for (Outcome f : failing) {
            String tag = f.declared() ? "listed in @Param" : "not listed";
            report.line("  [%s] %s / %s -> %s: %s"
                    .formatted(tag, f.engine(), f.bench().label(), f.status(), f.detail()));
        }
    }

    private static void printSummary(
            Report report, List<Bench> benches, List<Outcome> outcomes, Path transcriptFile, Path csv) {
        long ok = outcomes.stream().filter(o -> o.status() == Status.OK).count();
        long timedOut =
                outcomes.stream().filter(o -> o.status() == Status.TIMED_OUT).count();
        long brokenDeclared = outcomes.stream()
                .filter(o -> o.declared() && o.status() != Status.OK)
                .count();
        report.line("");
        report.line(("%d benchmarks, %d combos: %d OK, %d not OK (%d timed out, %d of them listed in @Param)")
                .formatted(benches.size(), outcomes.size(), ok, outcomes.size() - ok, timedOut, brokenDeclared));
        report.line("wrote " + transcriptFile + " and " + csv);
    }

    /** Prints to stdout as it goes and keeps a copy for the report file. */
    private static final class Report {
        private final StringBuilder transcript = new StringBuilder();

        void text(String s) {
            System.out.print(s);
            System.out.flush();
            transcript.append(s);
        }

        void line(String s) {
            text(s + System.lineSeparator());
        }

        void writeTo(Path file) throws java.io.IOException {
            Files.writeString(file, transcript, StandardCharsets.UTF_8);
        }
    }

    private static String padRight(String s, int width) {
        return s + " ".repeat(Math.max(0, width - s.length()));
    }

    private static void writeCsv(List<Outcome> outcomes, Path csv) throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add("engine,benchmark,declared,status,detail");
        for (Outcome o : outcomes) {
            lines.add("%s,%s,%s,%s,\"%s\""
                    .formatted(
                            o.engine(),
                            o.bench().label(),
                            o.declared(),
                            o.status(),
                            o.detail().replace("\"", "'")));
        }
        Files.write(csv, lines);
    }
}
