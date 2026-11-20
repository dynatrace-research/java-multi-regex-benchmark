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
import com.gliwka.hyperscan.wrapper.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

/**
 * Another open-source implementation of a hyperscan wrapper, taken from https://github.com/gliwka/hyperscan-java.
 */
public class HyperscanJava implements RegexEngine {
    private Database db;
    private Scanner scanner;
    private Integer count;

    private final ByteMatchEventHandler increment = new ByteMatchEventHandler() {
        @Override
        public boolean onMatch(Expression expression, long fromStringIndex, long toStringIndex) {
            count++;
            return true;
        }
    };

    private final ByteMatchEventHandler incrementAndExit = new ByteMatchEventHandler() {
        @Override
        public boolean onMatch(Expression expression, long fromStringIndex, long toStringIndex) {
            count++;
            return false;
        }
    };

    private MatchBuffer matches;
    private final ByteMatchEventHandler collectSpan = new ByteMatchEventHandler() {
        @Override
        public boolean onMatch(Expression expression, long fromStringIndex, long toStringIndex) {
            matches.add(expression.getId(), (int) fromStringIndex, (int) toStringIndex);
            return true;
        }
    };

    private boolean found;
    // returning false aborts the scan, so this stops at the first hit
    private final ByteMatchEventHandler firstMatch = new ByteMatchEventHandler() {
        @Override
        public boolean onMatch(Expression expression, long fromStringIndex, long toStringIndex) {
            found = true;
            return false;
        }
    };

    @Override
    public long compile(List<String> patterns, boolean requiresMatchOffset) {
        try {
            if (scanner != null) scanner.close();
            if (db != null) db.close();
            var expressions = new ArrayList<Expression>();
            for (int i = 0; i < patterns.size(); i++) {
                String pattern = patterns.get(i);
                EnumSet<ExpressionFlag> flags = EnumSet.of(ExpressionFlag.UTF8);
                if (requiresMatchOffset) {
                    flags.add(ExpressionFlag.SOM_LEFTMOST);
                }
                if (VectorscanUtils.needsPrefilter(pattern)) {
                    flags.add(ExpressionFlag.PREFILTER);
                }
                expressions.add(new Expression(pattern, flags, i));
            }
            db = Database.compile(expressions);
            scanner = new Scanner();
            scanner.allocScratch(db);
            return db.getSize() + scanner.getSize();
        } catch (Exception e) {
            throw new RuntimeException("Error during database compilation: " + e);
        }
    }

    @Override
    public int count(BenchmarkState state, int idx) {
        count = 0;
        scanner.scan(db, state.inputBuffers.get(idx), increment);
        return count;
    }

    @Override
    public int grep(BenchmarkState state, int idx) {
        count = 0;
        // hyperscan-java cannot scan a sub-range of an array, so each line is copied out
        ByteLines.forEach(
                state.inputBuffers.get(idx),
                (bytes, offset, length) ->
                        scanner.scan(db, Arrays.copyOfRange(bytes, offset, offset + length), incrementAndExit));
        return count;
    }

    @Override
    public void getAllMatches(BenchmarkState state, int idx) {
        matches = state.matches;
        matches.clear();
        scanner.scan(db, state.inputBuffers.get(idx), collectSpan);
    }

    @Override
    public void close() {
        try {
            scanner.close();
            db.close();
        } catch (IOException _) {
        }
    }
}
