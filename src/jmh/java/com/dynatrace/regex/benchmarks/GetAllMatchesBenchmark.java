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

import com.dynatrace.regex.auxiliary_counts.NormalizedCounts;
import com.dynatrace.regex.auxiliary_counts.TotalCounts;
import com.dynatrace.regex.workloads.*;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/**
 * Benchmarks measuring the throughput of collecting the start and end offsets of every match, against already compiled
 * patterns. This is the only benchmark where engines are compiled with {@code requiresMatchOffset=true}. There is one
 * method per workload, and the throughput is recorded through the auxiliary counters in {@code auxiliary_counts/}.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 2, time = 5)
@Timeout(time = 10)
@Fork(1)
public class GetAllMatchesBenchmark {
    @Benchmark
    public void randomLiterals(RandomLiteralsWorkload state, NormalizedCounts cState, TotalCounts dState) {
        getAllMatches(state, cState, dState);
    }

    @Benchmark
    public void randomRegexes(RandomRegexesWorkload state, NormalizedCounts cState, TotalCounts dState) {
        getAllMatches(state, cState, dState);
    }

    @Benchmark
    public void singleRegex(SingleRegexWorkload state, NormalizedCounts cState, TotalCounts dState) {
        getAllMatches(state, cState, dState);
    }

    @Benchmark
    public void loghub(LoghubWorkload state, NormalizedCounts cState, TotalCounts dState) {
        getAllMatches(state, cState, dState);
    }

    public static void getAllMatches(BenchmarkState state, NormalizedCounts nCounts, TotalCounts tCounts) {
        int chunk = state.nextChunk();
        for (int idx = state.chunkFrom(chunk); idx < state.chunkTo(chunk); idx++) {
            int count_sum;
            try {
                state.regexEngine.getAllMatches(state, idx);
                count_sum = state.matches.size();
            } catch (RuntimeException | Error failure) {
                state.recordBenchmarkFailure(failure);
                throw failure;
            }
            nCounts.matchesPerSecond += count_sum;
            tCounts.totalMatches += count_sum;
        }
        double processedMB = (double) state.chunkInputSize(chunk) / (1024.0 * 1024.0);
        nCounts.MBperSecond += processedMB;
        tCounts.totalMB += processedMB;
    }
}
