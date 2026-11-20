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
import java.util.ArrayList;
import java.util.List;
import org.safere.Matcher;
import org.safere.Pattern;

/**
 * An engine using SafeRE ({@code org.safere}), with one matcher per pattern.
 */
public class SafeRe implements RegexEngine {
    private List<Matcher> matchers;

    @Override
    public long compile(List<String> patterns, boolean requiresMatchOffset) {
        matchers = new ArrayList<>(patterns.size());
        for (String pattern : patterns) {
            matchers.add(Pattern.compile(pattern).matcher(""));
        }
        return 0;
    }

    @Override
    public int count(BenchmarkState state, int idx) {
        String input = state.inputs.get(idx);
        int count = 0;
        for (Matcher matcher : matchers) {
            matcher.reset(input);
            while (matcher.find()) {
                count++;
            }
        }
        return count;
    }

    @Override
    public int grep(BenchmarkState state, int idx) {
        return (int) state.inputs
                .get(idx)
                .lines()
                .filter(line -> {
                    for (Matcher matcher : matchers) {
                        matcher.reset(line);
                        if (matcher.find()) return true;
                    }
                    return false;
                })
                .count();
    }

    @Override
    public void getAllMatches(BenchmarkState state, int idx) {
        String input = state.inputs.get(idx);
        state.matches.clear();
        for (int p = 0; p < matchers.size(); p++) {
            Matcher matcher = matchers.get(p);
            matcher.reset(input);
            while (matcher.find()) {
                int start = matcher.start();
                int end = matcher.end();
                if (end > start) {
                    state.matches.add(p, start, end);
                }
            }
        }
    }
}
