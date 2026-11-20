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

import com.dynatrace.regex.utils.ByteLines;
import com.dynatrace.regex.utils.MatchBuffer;
import com.dynatrace.regex.utils.VectorscanUtils;
import com.dynatrace.regex.workloads.BenchmarkState;
import com.dynatrace.vectorscan4j.*;
import com.dynatrace.vectorscan4j.constants.ExecutionMode;
import java.util.ArrayList;
import java.util.List;

/**
 * An engine using {@code vectorscan4j} with a {@code BufferedMatchHandler} that collects matches in a native buffer,
 * so the Java callback runs once per batch instead of once per match. Patterns are compiled like in {@link Vectorscan}.
 */
public class VectorscanBatched implements RegexEngine {
    private BlockScanner scanner;
    private int count;
    private MatchBuffer matches;
    private final int BATCH_SIZE = 31457280;

    private final BufferedMatchHandler batchedIncrement = new BufferedMatchHandler(BATCH_SIZE, (_, nMatches) -> {
        count += nMatches;
        return true;
    });

    private final BufferedMatchHandler batchedGrepIncrement = new BufferedMatchHandler(BATCH_SIZE, (_, nMatches) -> {
        count += nMatches;
        return false;
    });

    private final BufferedMatchHandler batchedCollectSpans = new BufferedMatchHandler(BATCH_SIZE, (buf, nMatches) -> {
        // The native buffer holds (id, from, to) int triples in native byte order, like MatchBuffer does.
        matches.addAll(buf.asIntBuffer(), nMatches);
        return true;
    });

    @Override
    public long compile(List<String> patterns, boolean requiresMatchOffset) {
        var expressions = new ArrayList<Pattern>();
        for (var pattern : patterns) {
            expressions.add(new Pattern(pattern, VectorscanUtils.buildFlags(requiresMatchOffset, pattern)));
        }
        Database db = new Database(expressions, ExecutionMode.BLOCK_MODE);
        scanner = new BlockScanner(db);
        return db.getSize() + scanner.getScratchSize();
    }

    @Override
    public int count(BenchmarkState state, int idx) {
        count = 0;
        scanner.scan(state.inputBuffers.get(idx), batchedIncrement);
        return count;
    }

    @Override
    public int grep(BenchmarkState state, int idx) {
        count = 0;
        ByteLines.forEach(
                state.inputBuffers.get(idx),
                (bytes, offset, length) -> scanner.scan(bytes, offset, length, batchedGrepIncrement));
        return count;
    }

    @Override
    public void getAllMatches(BenchmarkState state, int idx) {
        matches = state.matches;
        matches.clear();
        scanner.scan(state.inputBuffers.get(idx), batchedCollectSpans);
    }
}
