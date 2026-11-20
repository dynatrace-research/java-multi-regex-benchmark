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
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

/**
 * A single regex pattern per benchmark run, each stressing one engine feature. Each {@link RegexAndInput}
 * also defines the input it is run on, so the feature actually gets exercised (e.g. a run of 'a's for the
 * ReDoS cases).
 */
@State(Scope.Thread)
public class SingleRegexWorkload extends RegexEngineWorkload {
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789 ";
    private static final int MARKER_PERIOD = 1024;
    private static final int DEFAULT_CHUNK_SIZE = 1 << 20;
    // Exponential backtracking would be far too slow on a megabyte of 'a's, so keep the chunks short.
    private static final int REDOS_CHUNK_SIZE = 20;
    private static final String LONG_LITERAL_TEXT = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    @Param
    public RegexAndInput workload;

    @Override
    protected void setupWorkload() {
        patterns = List.of(workload.regex);
        Random random = new Random(0);
        inputs = new ArrayList<>();
        inputs.add(workload.input.chunk(random));
    }

    /** Generates one input chunk of the given length. */
    @FunctionalInterface
    interface InputGenerator {
        String chunk(Random random);
    }

    public enum RegexAndInput {
        // Nested quantifiers: a backtracker tries exponentially many splits of the 'a' run before failing on 'b'.
        NESTED_QUANTIFIERS("(a+)+b", (random) -> "a".repeat(REDOS_CHUNK_SIZE)),
        // Overlapping alternatives under a star: the same exponential blow-up, via Fibonacci-many splits.
        OVERLAPPING_ALTERNATION("(a|aa)*c", (random) -> "a".repeat(REDOS_CHUNK_SIZE)),
        // n optional 'a's followed by n required 'a's, run on exactly n 'a's: a backtracker has to try about 2^n
        // ways of spending the optional ones before it finds the only one that matches.
        MANY_REPEATS(manyRepeatsRegex(10), (random) -> "a".repeat(10)),
        // Atomic group: once a+ consumes the run it cannot give characters back, so this stays linear.
        ATOMIC_GROUP("(?>a+)b", (random) -> text(random, DEFAULT_CHUNK_SIZE, "aaaab")),
        // Dense word matches: \w+ matches almost every position, stressing  per-match reporting overhead.
        DENSE_WORDS("\\w+", (random) -> text(random, DEFAULT_CHUNK_SIZE)),
        // Rare first character: 'Q' never occurs in the input, so a prefilter can skip everything.
        COMMON_FIRST_CHAR("[a-z]z\\d+", (random) -> text(random, DEFAULT_CHUNK_SIZE, "z42")),
        // Counted repetition of a class: NFA/DFA state count grows with the bound 50.
        COUNTED_CLASS("x{1,50}y", (random) -> text(random, DEFAULT_CHUNK_SIZE, "xxxxxxxxxxy")),
        // Counted repetition of digits: matches runs of 3 to 10 digits.
        COUNTED_DIGITS("\\d{3,10}", (random) -> text(random, DEFAULT_CHUNK_SIZE, "123456789")),
        // Leading greedy wildcard: find() must try every start position and extend to the last 'needle'.
        LEADING_WILDCARD(".*needle", (random) -> text(random, DEFAULT_CHUNK_SIZE, "needle")),
        // Wildcard between two literals: the greedy middle section lets matches span long distances.
        MIDDLE_WILDCARD("start.*end", (random) -> text(random, DEFAULT_CHUNK_SIZE, "start", "end")),
        // Anchored: ^ permits a match only at the start of the input, so the rest can be rejected immediately.
        ANCHORED_DIGITS("^\\d+$", (random) -> digits(random, DEFAULT_CHUNK_SIZE)),
        // Word boundaries: \b restricts matches to whole words, which can be checked cheaply.
        WORD_BOUNDARY("\\bcat\\b", (random) -> text(random, DEFAULT_CHUNK_SIZE, " cat ")),
        // Case-insensitive literal: engines usually need a case-folding path, which is slower.
        CASE_INSENSITIVE("(?i)error", (random) -> text(random, DEFAULT_CHUNK_SIZE, "ERROR")),
        // Long literal: 62 characters in one pattern, so literal-prefix skipping dominates.
        LONG_LITERAL(LONG_LITERAL_TEXT, (random) -> text(random, DEFAULT_CHUNK_SIZE, LONG_LITERAL_TEXT)),
        // Backreference: the engine must remember the captured word; many DFA/NFA engines do not support this.
        BACKREFERENCE("(\\w+) \\1", (random) -> text(random, DEFAULT_CHUNK_SIZE, "hello hello")),
        // Lookahead: checks the following text without consuming it; many engines do not support this.
        LOOKAHEAD("foo(?=bar)", (random) -> text(random, DEFAULT_CHUNK_SIZE, "foobar")),
        // Email address: real-world character classes plus a repeated TLD class.
        EMAIL(
                "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}",
                (random) -> text(random, DEFAULT_CHUNK_SIZE, "user.name@example.com")),
        // IPv4 address: bounded repetition combined with word boundaries.
        IPV4("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b", (random) -> text(random, DEFAULT_CHUNK_SIZE, " 192.168.1.1 ")),
        // ISO date: fixed-width digit groups separated by literal '-'.
        ISO_DATE("\\d{4}-\\d{2}-\\d{2}", (random) -> text(random, DEFAULT_CHUNK_SIZE, "2026-10-12")),
        // ISO timestamp
        ISO_TIMESTAMP(
                "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2})?",
                (random) -> text(random, DEFAULT_CHUNK_SIZE, "2023-06-15T13:45:30.123+02:00")),
        // URL: a scheme alternation followed by a wide character class that runs to the end of the token.
        URL("https?://[\\w./-]+", (random) -> text(random, DEFAULT_CHUNK_SIZE, "https://example.org/path")),
        // UUID
        UUID(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                (random) -> text(random, DEFAULT_CHUNK_SIZE, "123e4567-e89b-12d3-a456-426614174000"));

        private final String regex;
        private final InputGenerator input;

        RegexAndInput(String regex, InputGenerator input) {
            this.regex = regex;
            this.input = input;
        }
    }

    private static String manyRepeatsRegex(int repeats) {
        return "a?".repeat(repeats) + "a".repeat(repeats);
    }

    /** Random text over {@link #ALPHABET}, with each marker written at regular intervals. */
    private static String text(Random random, int length, String... markers) {
        char[] chars = LoadGenerator.randomCharArr(random, length, ALPHABET);
        for (int m = 0; m < markers.length; m++) {
            String marker = markers[m];
            // Offsets differ per marker so that markers placed together do not overwrite each other.
            for (int pos = 512 + 64 * m; pos + marker.length() <= length; pos += MARKER_PERIOD) {
                marker.getChars(0, marker.length(), chars, pos);
            }
        }
        return new String(chars);
    }

    private static String digits(Random random, int length) {
        return new String(LoadGenerator.randomCharArr(random, length, "0123456789"));
    }
}
