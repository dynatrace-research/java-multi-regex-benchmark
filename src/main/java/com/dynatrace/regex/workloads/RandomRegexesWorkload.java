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
import com.dynatrace.regex.utils.RegexGenerator;
import java.util.ArrayList;
import java.util.Random;
import org.openjdk.jmh.annotations.Param;

/**
 * A workload containing random string inputs, and a list of {@code nPatterns} random regexes (see
 * {@code RegexGenerator}). Inputs are generated until their total size reaches {@code MIN_TOTAL_INPUT_SIZE}.
 */
public class RandomRegexesWorkload extends RegexEngineWorkload {
    private static final int MIN_TOTAL_INPUT_SIZE = 1024 * 1024;

    @Param({"1", "2", "4", "8", "16", "32", "64", "128", "256"})
    public int nPatterns;

    @Param({"1024"})
    public int inputSize;

    @Override
    protected void setupWorkload() {
        Random random = new Random(0);
        inputs = new ArrayList<>();
        for (int i = 0; i < MIN_TOTAL_INPUT_SIZE; i += inputSize) {
            inputs.add(new String(LoadGenerator.randomByteArr(random, inputSize)));
        }
        patterns = RegexGenerator.generateRandomRegexes(nPatterns);
    }
}
