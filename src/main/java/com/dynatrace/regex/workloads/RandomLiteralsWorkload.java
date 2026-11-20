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

import com.dynatrace.regex.utils.LoadGenerator;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.openjdk.jmh.annotations.*;

/**
 * A workload containing random string inputs, and a list of {@code nPatterns} random literal patterns of
 * {@code patternSize} characters. {@code matchDensity} controls how many matches are planted in the input. Inputs are
 * generated until their total size reaches {@code MIN_TOTAL_INPUT_SIZE}.
 */
@State(Scope.Thread)
public class RandomLiteralsWorkload extends LiteralEngineWorkload {
    private static final int MIN_TOTAL_INPUT_SIZE = 1024 * 1024;

    @Param({"1", "2", "4", "8", "16", "32", "64", "128", "256"})
    public int nPatterns;

    @Param({"9"})
    public int patternSize;

    @Param({"1024"})
    public int inputSize;

    @Param({"0.0", "0.001", "0.01", "0.1"})
    public double matchDensity;

    @Override
    protected void setupWorkload() {
        int maxMatchesPerInput = inputSize / patternSize;
        double maxMatchDensity = maxMatchesPerInput / (double) inputSize;
        if (matchDensity > maxMatchDensity) {
            throw new IllegalArgumentException(String.format(
                    "RandomLiteralsWorkload(inputSize=%d, patternSize=%d) can only support matchDensity up to %f, but got %f.",
                    inputSize, patternSize, maxMatchDensity, matchDensity));
        }

        Random random = new Random(0);
        patterns = IntStream.range(0, nPatterns)
                .mapToObj(i -> new String(LoadGenerator.randomByteArr(random, patternSize)))
                .collect(Collectors.toCollection(ArrayList::new));

        int nInputs = (int) Math.ceil((double) MIN_TOTAL_INPUT_SIZE / inputSize);
        int totalMatches = (int) Math.round(matchDensity * (long) nInputs * inputSize);

        // some number of inputs will have (at least) this many matches, the rest of the inputs will have this many + 1.
        int nMatchesPerInput = totalMatches / nInputs;

        // this many inputs will have nMatchesPerInput + 1 inputs.
        int nHighMatchInputs = totalMatches % nInputs;

        // this many inputs will have nMatchesPerInput inputs.
        int nLowMatchInputs = nInputs - nHighMatchInputs;

        inputs = new ArrayList<>(nInputs);

        for (int i = 0; i < nHighMatchInputs; i++) {
            inputs.add(createInput(inputSize, nMatchesPerInput + 1, patterns, random));
        }
        for (int i = 0; i < nLowMatchInputs; i++) {
            inputs.add(createInput(inputSize, nMatchesPerInput, patterns, random));
        }
    }

    private String createInput(int length, int n, List<String> patterns, Random random) {
        // first, generate a random lowercase char array
        byte[] ans = LoadGenerator.randomByteArr(random, length);

        // then, plant nMatches random patterns contiguously in the array
        for (int mIdx = 0; mIdx < n; mIdx++) {
            String randPattern = patterns.get(random.nextInt(patterns.size()));
            byte[] patternBytes = randPattern.getBytes(StandardCharsets.UTF_8);
            var lowIdx = mIdx * patternSize;
            System.arraycopy(patternBytes, 0, ans, lowIdx, patternSize);
        }

        return new String(ans);
    }
}
