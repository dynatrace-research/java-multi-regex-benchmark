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
package com.dynatrace.regex.workloads;

import com.dynatrace.regex.engines.*;
import com.dynatrace.regex.utils.MatchBuffer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.BenchmarkParams;

/**
 *  The base BenchmarkState defines an input, a list of patterns to match on the input, and a choice of a regex engine.
 *  Subclasses (the workloads) generate {@link #patterns} and {@link #inputs} in {@code setupWorkload()}. The trial setup
 *  then encodes the inputs to UTF-8 and compiles the engine. A failure during setup or matching is appended to the
 *  error log ({@code build/results/jmh/errors.csv}, or the file in the {@code jmh.errorLog} system property).
 */
@State(Scope.Thread)
@AuxCounters(AuxCounters.Type.EVENTS)
public abstract class BenchmarkState {
    /** The input as UTF-16 encoded Java Strings. */
    public List<String> inputs;
    /**
     * UTF-8 encoded copies of {@link #inputs}, in the same order. Engines that match on bytes can use these instead
     * of encoding the Strings on every call. Treat the arrays as read-only.
     */
    public List<byte[]> inputBuffers;
    /** total byte size of the input data */
    public long totalInputSize;
    /** the set of patterns one wants to match on the input. */
    public List<String> patterns;

    public int patternCount;

    /**
     * Start index in {@link #inputs} of every chunk, plus the end index of the last one, so chunk {@code c} covers
     * {@code inputs[chunkStart[c]..chunkStart[c + 1])}. A workload may set this in {@code setupWorkload()} to let a
     * benchmark invocation scan only one chunk at a time (see {@link #nextChunk()}). Without it, all inputs form a
     * single chunk.
     */
    protected int[] chunkStart;

    private long[] chunkInputSize;
    private int nextChunk;

    /**
     * The buffer that {@link RegexEngine#getAllMatches} fills. It is allocated once with the state, so collecting
     * matches does not allocate in the measured code.
     */
    public final MatchBuffer matches = new MatchBuffer(INITIAL_MATCH_CAPACITY);

    private static final int INITIAL_MATCH_CAPACITY = 1 << 16;

    public RegexEngine regexEngine;

    protected abstract String getEngineName();

    protected abstract void setupWorkload();

    private static final String ERROR_LOG_PROPERTY = "jmh.errorLog";
    private static final String DEFAULT_ERROR_LOG = "build/results/jmh/errors.csv";

    /** Kept so benchmark bodies can report a measurement-time failure without every @Benchmark
     *  method having to declare a BenchmarkParams parameter. Private, so it stays out of the way
     *  of JMH's state/counter field scanning. */
    private BenchmarkParams benchmarkParams;

    /**
     * Returns the index of the chunk that the next benchmark invocation scans, and advances to the following chunk
     * (wrapping around), so that successive invocations cycle through all chunks.
     */
    public int nextChunk() {
        int chunk = nextChunk;
        nextChunk = (chunk + 1) % chunkInputSize.length;
        return chunk;
    }

    /** The index in {@link #inputs} of the first input of a chunk. */
    public int chunkFrom(int chunk) {
        return chunkStart[chunk];
    }

    /** The index in {@link #inputs} after the last input of a chunk. */
    public int chunkTo(int chunk) {
        return chunkStart[chunk + 1];
    }

    /** The size of a chunk's inputs, measured like {@link #totalInputSize}. */
    public long chunkInputSize(int chunk) {
        return chunkInputSize[chunk];
    }

    @Setup(Level.Trial)
    public final void setupState(BenchmarkParams benchmarkParams) {
        this.benchmarkParams = benchmarkParams;
        try {
            setupWorkload();
            setupBenchmark();
        } catch (RuntimeException | Error failure) {
            recordFailure(benchmarkParams, failure);
            throw failure;
        }
    }

    /**
     * Logs a failure thrown from a benchmark body. Callers are expected to rethrow afterwards: JMH
     * then aborts the benchmark and emits no result for it, and the formatted results pick the
     * combination up from the error log instead of reporting a throughput for a run that only threw.
     */
    public void recordBenchmarkFailure(Throwable failure) {
        if (benchmarkParams != null) {
            recordFailure(benchmarkParams, failure);
        }
    }

    /**
     * Appends a row to the error log. A failure here aborts the benchmark, and JMH emits no result
     * entry at all for an aborted benchmark, so this side channel is the only way the combination can
     * still surface as an Error row in the formatted results.
     */
    private static void recordFailure(BenchmarkParams benchmarkParams, Throwable failure) {
        List<String> keys = new ArrayList<>();
        for (Object key : benchmarkParams.getParamsKeys()) {
            keys.add(String.valueOf(key));
        }
        Collections.sort(keys);
        StringBuilder params = new StringBuilder();
        for (String key : keys) {
            if (params.length() > 0) {
                params.append(';');
            }
            params.append(key).append('=').append(benchmarkParams.getParam(key));
        }

        Path log = Path.of(System.getProperty(ERROR_LOG_PROPERTY, DEFAULT_ERROR_LOG));
        try {
            Path parent = log.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            CSVFormat format = Files.notExists(log)
                    ? CSVFormat.DEFAULT
                            .builder()
                            .setHeader("benchmark", "params", "error")
                            .build()
                    : CSVFormat.DEFAULT;
            try (var writer = Files.newBufferedWriter(
                            log, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    var printer = new CSVPrinter(writer, format)) {
                printer.printRecord(benchmarkParams.getBenchmark(), params.toString(), rootCause(failure));
            }
        } catch (IOException ignored) {
            // bookkeeping must never mask the original failure
        }
    }

    private static String rootCause(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private boolean requiresMatchOffset() {
        return benchmarkParams.getBenchmark().contains("GetAllMatchesBenchmark");
    }

    /** Fills {@link #inputBuffers} with the UTF-8 encoding of every entry of {@link #inputs}. */
    protected void encodeInputs() {
        inputBuffers = new ArrayList<>(inputs.size());
        for (String input : inputs) {
            inputBuffers.add(input.getBytes(StandardCharsets.UTF_8));
        }
    }

    protected void setupBenchmark() {
        assert patterns != null;
        assert inputs != null;
        totalInputSize = inputs.stream().mapToLong(str -> (long) str.length()).sum();
        if (chunkStart == null) {
            chunkStart = new int[] {0, inputs.size()};
        }
        chunkInputSize = new long[chunkStart.length - 1];
        for (int c = 0; c < chunkInputSize.length; c++) {
            for (int idx = chunkStart[c]; idx < chunkStart[c + 1]; idx++) {
                chunkInputSize[c] += inputs.get(idx).length();
            }
        }
        encodeInputs();
        patternCount = patterns.size();
        regexEngine = RegexEngine.fromString(getEngineName());
        regexEngine.compile(patterns, requiresMatchOffset());
    }

    @TearDown(Level.Trial)
    public void teardownEngine() {
        regexEngine.close();
    }
}
