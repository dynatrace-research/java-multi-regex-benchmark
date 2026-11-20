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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.openjdk.jmh.annotations.*;

/**
 * A trivial workload, where matching should be extremely fast, and overhead from dense matches can be observed.
 * It matches the single-character pattern {@code "a"} against a filler string, and {@code matchDensity} controls how
 * dense the matches are (the worst case for per-match wrapper overhead).
 */
@State(Scope.Thread)
public class TrivialWorkload extends LiteralEngineWorkload {
    private static final int MIN_TOTAL_INPUT_SIZE = 1024 * 1024 * 4;

    @Param({"0", "0.001", "0.01", "0.1"})
    public double matchDensity;

    @Param({"1048576"})
    public int inputSize;

    @Override
    protected void setupWorkload() {
        patterns = List.of("a");
        int nInputs = (int) Math.ceil((double) MIN_TOTAL_INPUT_SIZE / inputSize);
        int totalMatches = (int) Math.round(matchDensity * (long) nInputs * inputSize);

        // some number of inputs will have (at least) this many matches, the rest of the inputs will have this many + 1.
        int nMatchesPerInput = totalMatches / nInputs;

        // this many inputs will have nMatchesPerInput + 1 inputs.
        int nHighMatchInputs = totalMatches % nInputs;

        // this many inputs will have nMatchesPerInput inputs.
        int nLowMatchInputs = nInputs - nHighMatchInputs;

        inputs = new ArrayList<>();
        for (int i = 0; i < nHighMatchInputs; i++) {
            inputs.add(createInput(inputSize, nMatchesPerInput + 1));
        }
        for (int i = 0; i < nLowMatchInputs; i++) {
            inputs.add(createInput(inputSize, nMatchesPerInput));
        }
    }

    private String createInput(int length, int n) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) 'z');
        if (n > 0) {
            int offset = length / n;
            for (int i = 0; i < n; i++) {
                bytes[i * offset] = 'a';
            }
        }
        return new String(bytes);
    }
}
