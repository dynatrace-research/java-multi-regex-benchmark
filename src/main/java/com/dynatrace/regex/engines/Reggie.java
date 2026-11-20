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

import com.datadoghq.reggie.runtime.MatchResult;
import com.datadoghq.reggie.runtime.ReggieMatcher;
import com.dynatrace.regex.workloads.BenchmarkState;
import java.util.ArrayList;
import java.util.List;

/**
 * An engine using {@code com.datadoghq:reggie}, with one matcher per pattern.
 */
public class Reggie implements RegexEngine {
    private List<ReggieMatcher> matchers;

    @Override
    public long compile(List<String> patterns, boolean requiresMatchOffset) {
        matchers = new ArrayList<>(patterns.size());
        for (String pattern : patterns) {
            matchers.add(com.datadoghq.reggie.Reggie.compile(pattern));
        }
        return 0;
    }

    @Override
    public int count(BenchmarkState state, int idx) {
        String input = state.inputs.get(idx);
        int count = 0;
        for (ReggieMatcher matcher : matchers) {
            count += matcher.findAll(input).size();
        }
        return count;
    }

    @Override
    public int grep(BenchmarkState state, int idx) {
        return (int) state.inputs
                .get(idx)
                .lines()
                .filter(line -> matchers.stream().anyMatch(matcher -> matcher.find(line)))
                .count();
    }

    @Override
    public void getAllMatches(BenchmarkState state, int idx) {
        String input = state.inputs.get(idx);
        state.matches.clear();
        for (int p = 0; p < matchers.size(); p++) {
            ReggieMatcher matcher = matchers.get(p);
            for (MatchResult match : matcher.findAll(input)) {
                if (match.end() > match.start()) {
                    state.matches.add(p, match.start(), match.end());
                }
            }
        }
    }
}
