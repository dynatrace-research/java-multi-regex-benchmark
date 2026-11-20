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
package com.dynatrace.regex.benchmarks;

import com.dynatrace.regex.auxiliary_counts.MemUsageCounts;
import com.dynatrace.regex.workloads.*;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jol.info.GraphLayout;

/**
 * Benchmarks measuring the latency of the compilation/setup step of a regex engine.
 * Also collects memory usage measurements.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 1, time = 5)
@Timeout(time = 10)
@Fork(1)
public class CompilationBenchmark {
    @Benchmark
    public void randomLiterals(RandomLiteralsWorkload load, MemUsageCounts mUsage) {
        compileAndMeasureMemory(load, mUsage);
    }

    @Benchmark
    public void randomRegexes(RandomRegexesWorkload load, MemUsageCounts mUsage) {
        compileAndMeasureMemory(load, mUsage);
    }

    @Benchmark
    public void singleRegex(SingleRegexWorkload load, MemUsageCounts mUsage) {
        compileAndMeasureMemory(load, mUsage);
    }

    @Benchmark
    public void loghub(LoghubWorkload load, MemUsageCounts mUsage) {
        compileAndMeasureMemory(load, mUsage);
    }

    private static void compileAndMeasureMemory(BenchmarkState load, MemUsageCounts mUsage) {
        try {
            mUsage.nativeMemoryUsage = load.regexEngine.compile(load.patterns, false);
            mUsage.heapUsage = GraphLayout.parseInstance(load.regexEngine).totalSize();
        } catch (RuntimeException | Error failure) {
            load.recordBenchmarkFailure(failure);
            throw failure;
        }
    }
}
