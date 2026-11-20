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
import com.hankcs.algorithm.AhoCorasickDoubleArrayTrie;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A literal multi-pattern engine, using {@code com.hankcs:aho-corasick-double-array-trie}. The patterns are matched as
 * literal strings (no regex syntax), so it only runs on workloads that extend {@code LiteralEngineWorkload}.
 */
public class AhoCorasick2 implements RegexEngine {
    private AhoCorasickDoubleArrayTrie<Integer> trie;

    @Override
    public long compile(List<String> patterns, boolean requiresMatchOffset) {
        Map<String, Integer> keywordMap = new LinkedHashMap<>(patterns.size());
        for (int i = 0; i < patterns.size(); i++) {
            keywordMap.put(patterns.get(i), i);
        }
        trie = new com.hankcs.algorithm.AhoCorasickDoubleArrayTrie<>();
        trie.build(keywordMap);
        return 0;
    }

    @Override
    public int count(BenchmarkState state, int idx) {
        return trie.parseText(state.inputs.get(idx)).size();
    }

    @Override
    public int grep(BenchmarkState state, int idx) {
        return (int) state.inputs
                .get(idx)
                .lines()
                .filter(line -> trie.findFirst(line) != null)
                .count();
    }

    @Override
    public void getAllMatches(BenchmarkState state, int idx) {
        String input = state.inputs.get(idx);
        state.matches.clear();
        int lastEnd = -1;
        for (var hit : trie.parseText(input)) {
            if (hit.begin >= lastEnd && hit.end > hit.begin && hit.end <= input.length()) {
                state.matches.add(hit.value, hit.begin, hit.end);
                lastEnd = hit.end;
            }
        }
    }
}
