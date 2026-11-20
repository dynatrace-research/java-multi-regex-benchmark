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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.jcodings.specific.UTF8Encoding;
import org.joni.Matcher;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Syntax;

/**
 * An engine using Joni, the Java port of the Oniguruma regex engine that JRuby uses ({@code org.joni}), with one regex
 * per pattern. The patterns are read with Joni's Java syntax, and they are matched on the UTF-8 bytes of the inputs
 * ({@link BenchmarkState#inputBuffers}), so reported offsets are byte offsets, as for the Vectorscan engines.
 *
 * <p>A Joni {@link Matcher} is bound to the bytes it was created for, so unlike the other Java engines this one
 * creates a matcher per pattern and input, instead of resetting a matcher. {@link #grep} searches line by line in the
 * whole input, so a match of a pattern that can match a newline may reach past the end of its line.
 *
 * <p>AI-generated: this class was written by an AI coding agent (Claude Code) with little manual editing. It is used
 * through the {@code engine} parameter of the workloads, see {@link RegexEngine#fromString}.
 */
public class Joni implements RegexEngine {
    private static final byte NEWLINE = '\n';

    private List<Regex> regexes;

    @Override
    public long compile(List<String> patterns, boolean requiresMatchOffset) {
        regexes = new ArrayList<>(patterns.size());
        for (String pattern : patterns) {
            byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);
            regexes.add(new Regex(bytes, 0, bytes.length, Option.DEFAULT, UTF8Encoding.INSTANCE, Syntax.Java));
        }
        return 0;
    }

    @Override
    public int count(BenchmarkState state, int idx) {
        byte[] input = state.inputBuffers.get(idx);
        int count = 0;
        for (Regex regex : regexes) {
            Matcher matcher = regex.matcher(input);
            int pos = 0;
            while (pos <= input.length && matcher.search(pos, input.length, Option.DEFAULT) >= 0) {
                count++;
                pos = nextSearchStart(input, matcher);
            }
        }
        return count;
    }

    @Override
    public int grep(BenchmarkState state, int idx) {
        byte[] input = state.inputBuffers.get(idx);
        List<Matcher> matchers = new ArrayList<>(regexes.size());
        for (Regex regex : regexes) {
            matchers.add(regex.matcher(input));
        }
        int matchingLines = 0;
        int lineStart = 0;
        while (lineStart <= input.length) {
            int lineEnd = lineStart;
            while (lineEnd < input.length && input[lineEnd] != NEWLINE) {
                lineEnd++;
            }
            for (Matcher matcher : matchers) {
                if (matcher.search(lineStart, lineEnd, Option.DEFAULT) >= 0) {
                    matchingLines++;
                    break;
                }
            }
            lineStart = lineEnd + 1;
        }
        return matchingLines;
    }

    @Override
    public void getAllMatches(BenchmarkState state, int idx) {
        byte[] input = state.inputBuffers.get(idx);
        state.matches.clear();
        for (int p = 0; p < regexes.size(); p++) {
            Matcher matcher = regexes.get(p).matcher(input);
            int pos = 0;
            while (pos <= input.length && matcher.search(pos, input.length, Option.DEFAULT) >= 0) {
                int start = matcher.getBegin();
                int end = matcher.getEnd();
                if (end > start) {
                    state.matches.add(p, start, end);
                }
                pos = nextSearchStart(input, matcher);
            }
        }
    }

    /** Where to continue after a match: at its end, or one character later if it was empty (like java.util.regex). */
    private static int nextSearchStart(byte[] input, Matcher matcher) {
        int end = matcher.getEnd();
        if (end > matcher.getBegin()) {
            return end;
        }
        int next = end + 1;
        while (next < input.length && (input[next] & 0xC0) == 0x80) {
            next++; // skip the continuation bytes of a multi-byte character
        }
        return next;
    }
}
