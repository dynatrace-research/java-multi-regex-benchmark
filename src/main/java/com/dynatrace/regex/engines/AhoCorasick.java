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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.ahocorasick.trie.Trie;

/**
 * A literal multi-pattern engine, using {@code org.ahocorasick:ahocorasick}. The patterns are matched as literal
 * strings (no regex syntax), so it only runs on workloads that extend {@code LiteralEngineWorkload}. The library only
 * reports the matched keyword, so the pattern id of a match is looked up by hashing the keyword. For duplicate
 * patterns, the id is the index of the last duplicate.
 */
public class AhoCorasick implements RegexEngine {
    private Trie trie;
    private final Map<String, Integer> patternIds = new HashMap<>();

    @Override
    public long compile(List<String> patterns, boolean requiresMatchOffset) {
        patternIds.clear();
        for (int i = 0; i < patterns.size(); i++) {
            patternIds.put(patterns.get(i), i);
        }
        trie = Trie.builder().ignoreOverlaps().addKeywords(patterns).build();
        return 0;
    }

    @Override
    public int count(BenchmarkState state, int idx) {
        return trie.parseText(state.inputs.get(idx)).size();
    }

    @Override
    public int grep(BenchmarkState state, int idx) {
        return (int) state.inputs.get(idx).lines().filter(trie::containsMatch).count();
    }

    @Override
    public void getAllMatches(BenchmarkState state, int idx) {
        String input = state.inputs.get(idx);
        state.matches.clear();
        for (var emit : trie.parseText(input)) {
            int start = emit.getStart();
            int endExclusive = emit.getEnd() + 1;
            if (start >= 0 && endExclusive > start && endExclusive <= input.length()) {
                state.matches.add(patternIds.get(emit.getKeyword()), start, endExclusive);
            }
        }
    }
}
