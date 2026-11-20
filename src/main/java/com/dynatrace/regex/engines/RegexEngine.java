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
package com.dynatrace.regex.engines;

import com.dynatrace.regex.workloads.BenchmarkState;
import java.util.List;

/**
 * The common interface that every regex engine wrapper implements: {@link #compile} builds the engine for a set of patterns, and {@link #count}, {@link #grep} and
 * {@link #getAllMatches} match one input of a {@link BenchmarkState}. Engines are created by class name through
 * {@link #fromString}, and the {@code engine} parameter of the workloads selects which ones run.
 *
 * <p>IMPORTANT NOTE: While the regex engine implementations all fulfill the same interface, they nevertheless partly
 * differ in their matching semantics.
 *
 * <p>To add an engine, implement this interface in this package and add its class name to the {@code engine}
 * {@code @Param} lists of {@code LiteralEngineWorkload} and {@code RegexEngineWorkload} where it is valid.
 */
public interface RegexEngine {
    /**
     * Compile the list of patterns. Usually this is something that has to be done once for a fixed set of patterns.
     * Some regex engines don't feature a separate compilation step (e.g. Lazy DFAs), in which case this method should just return.
     *
     * @param requiresMatchOffset whether the engine will be asked to return match start offsets (e.g. via {@link #getAllMatches}).
     *                            Engines that can avoid tracking start offsets (e.g. by omitting SOM_LEFTMOST) may use this to reduce overhead.
     * @return the off-heap memory used by the compiled internal data structure in bytes.
     */
    long compile(List<String> patterns, boolean requiresMatchOffset);

    default long compile(List<String> patterns) {
        return compile(patterns, true);
    }

    /**
     * Compute the number of found matches in one input of the state. An engine chooses whether to match on the UTF-16
     * {@code state.inputs.get(idx)} or on its UTF-8 encoded copy {@code state.inputBuffers.get(idx)}.
     *
     * @param idx the index of the input, in both {@link BenchmarkState#inputs} and {@link BenchmarkState#inputBuffers}
     */
    int count(BenchmarkState state, int idx);

    /**
     * Given one input of the state, count the number of lines (i.e. newline-separated substrings) that match at least one of the patterns.
     *
     * @param idx the index of the input, as in {@link #count}
     */
    int grep(BenchmarkState state, int idx);

    /**
     * Collect all matches in one input of the state into {@link BenchmarkState#matches}, which is cleared first. The
     * buffer is reused across calls, so an implementation must not allocate per match.
     *
     * @param idx the index of the input, as in {@link #count}
     */
    void getAllMatches(BenchmarkState state, int idx);

    /**
     * Close the regex engine. Required by the {@HyperscanJavaEngine} in order to free native memory.
     */
    default void close() {
        return;
    }

    static RegexEngine fromString(String engineName) {
        try {
            return (RegexEngine) Class.forName("com.dynatrace.regex.engines." + engineName)
                    .getDeclaredConstructor()
                    .newInstance();
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Unknown engine: " + engineName, e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Could not instantiate engine: : " + engineName, e);
        }
    }
}
