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
package com.dynatrace.regex.utils;

import java.nio.IntBuffer;
import java.util.Arrays;

/**
 * A reusable buffer of matches (pattern id, start and end offset) that engines fill in {@code getAllMatches}, so that
 * collecting matches does not allocate an object per match. The matches are stored in one {@code int[]}. The buffer is
 * allocated up front with room for {@code initialCapacity} matches, {@link #clear()} keeps that storage, and the buffer
 * only grows (doubling) when a run finds more matches than it has room for. After the warmup, a run therefore
 * allocates nothing as long as it finds no more matches than an earlier run did.
 */
public final class MatchBuffer {
    /** The pattern id that an engine writes for a match when it does not compute which pattern matched. */
    public static final int UNKNOWN_PATTERN = -1;

    private static final int INTS_PER_MATCH = 3;

    private int[] spans;
    private int size;

    public MatchBuffer(int initialCapacity) {
        spans = new int[INTS_PER_MATCH * Math.max(1, initialCapacity)];
    }

    /** Removes all matches, keeping the allocated storage. */
    public void clear() {
        size = 0;
    }

    /**
     * Appends one match.
     *
     * @param patternId the index of the pattern that matched in the compiled list of patterns, or
     *     {@link #UNKNOWN_PATTERN} if the engine does not compute it
     */
    public void add(int patternId, int start, int end) {
        int i = INTS_PER_MATCH * size;
        if (i == spans.length) {
            spans = Arrays.copyOf(spans, 2 * spans.length);
        }
        spans[i] = patternId;
        spans[i + 1] = start;
        spans[i + 2] = end;
        size++;
    }

    /**
     * Appends {@code count} matches with one bulk copy, from the start of a buffer that holds them as consecutive
     * {@code (patternId, start, end)} int triples, the layout that this buffer uses internally.
     */
    public void addAll(IntBuffer source, int count) {
        int needed = INTS_PER_MATCH * (size + count);
        if (needed > spans.length) {
            spans = Arrays.copyOf(spans, Math.max(needed, 2 * spans.length));
        }
        source.get(0, spans, INTS_PER_MATCH * size, INTS_PER_MATCH * count);
        size += count;
    }

    /** The number of matches in the buffer. */
    public int size() {
        return size;
    }

    /** The pattern id of a match, or {@link #UNKNOWN_PATTERN}. */
    public int patternId(int index) {
        return spans[INTS_PER_MATCH * index];
    }

    public int start(int index) {
        return spans[INTS_PER_MATCH * index + 1];
    }

    public int end(int index) {
        return spans[INTS_PER_MATCH * index + 2];
    }
}
