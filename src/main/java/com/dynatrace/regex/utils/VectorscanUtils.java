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

import com.dynatrace.vectorscan4j.constants.PatternFlag;
import java.util.EnumSet;

/** Helpers for compiling patterns with Vectorscan. */
public final class VectorscanUtils {
    private VectorscanUtils() {}

    /**
     * Computes whether the pattern uses a construct that Vectorscan rejects unless it is compiled in prefilter mode:
     * backreferences, lookaround assertions, atomic groups, possessive quantifiers and conditionals. Escapes,
     * {@code \Q..\E} literals, character classes and {@code (?#..)} comments are skipped, so that e.g. {@code [(?=]}
     * or {@code \p{L}+} are not mistaken for one.
     *
     * This method is fully AI-generated and not independently tested.
     */
    public static boolean needsPrefilter(String pattern) {
        int n = pattern.length();
        // Whether the previous token is a quantifier: a following '?' makes it lazy and a following '+' possessive.
        boolean quantified = false;
        int i = 0;
        while (i < n) {
            char c = pattern.charAt(i);
            switch (c) {
                case '\\' -> {
                    char e = i + 1 < n ? pattern.charAt(i + 1) : 0;
                    char after = i + 2 < n ? pattern.charAt(i + 2) : 0;
                    if (e >= '1' && e <= '9') return true; // \1
                    if ((e == 'k' || e == 'g') && (after == '<' || after == '{' || after == '\'')) return true; // \k<n>
                    if (e == 'Q') {
                        int end = pattern.indexOf("\\E", i + 2);
                        i = end < 0 ? n : end + 2;
                    } else if ((e == 'p' || e == 'P' || e == 'x' || e == 'o' || e == 'N') && after == '{') {
                        int end = pattern.indexOf('}', i + 3);
                        i = end < 0 ? n : end + 1;
                    } else {
                        i += 2;
                    }
                    quantified = false;
                }
                case '[' -> {
                    int j = i + 1;
                    if (j < n && pattern.charAt(j) == '^') j++;
                    if (j < n && pattern.charAt(j) == ']') j++; // a leading ']' is a literal
                    while (j < n && pattern.charAt(j) != ']') {
                        if (pattern.charAt(j) == '\\') {
                            j += 2;
                        } else if (pattern.startsWith("[:", j)) {
                            int end = pattern.indexOf(":]", j + 2);
                            j = end < 0 ? n : end + 2;
                        } else {
                            j++;
                        }
                    }
                    i = j + 1;
                    quantified = false;
                }
                case '(' -> {
                    quantified = false;
                    if (i + 1 >= n || pattern.charAt(i + 1) != '?') {
                        i++;
                        break;
                    }
                    char d = i + 2 < n ? pattern.charAt(i + 2) : 0;
                    char d2 = i + 3 < n ? pattern.charAt(i + 3) : 0;
                    if (d == '=' || d == '!') return true; // (?= (?!
                    if (d == '<' && (d2 == '=' || d2 == '!')) return true; // (?<= (?<!
                    if (d == '>') return true; // (?>
                    if (d == '(') return true; // (?(1)..|..)
                    if (d == 'P' && d2 == '=') return true; // (?P=name)
                    if (d == '#') {
                        int end = pattern.indexOf(')', i + 3);
                        i = end < 0 ? n : end + 1;
                    } else {
                        i += 2;
                    }
                }
                case '*', '+', '?' -> {
                    if (quantified) {
                        if (c == '+') return true; // a++ a*+ a?+ a{2}+
                        quantified = false; // a+? is lazy
                    } else {
                        quantified = true;
                    }
                    i++;
                }
                case '{' -> {
                    int j = i + 1;
                    while (j < n && Character.isDigit(pattern.charAt(j))) j++;
                    boolean counted = j > i + 1;
                    if (counted && j < n && pattern.charAt(j) == ',') {
                        j++;
                        while (j < n && Character.isDigit(pattern.charAt(j))) j++;
                    }
                    if (counted && j < n && pattern.charAt(j) == '}') {
                        i = j + 1;
                        quantified = true;
                    } else {
                        i++;
                        quantified = false;
                    }
                }
                default -> {
                    quantified = false;
                    i++;
                }
            }
        }
        return false;
    }

    public static EnumSet<PatternFlag> buildFlags(boolean requiresMatchOffset, String pattern) {
        EnumSet<PatternFlag> flags = EnumSet.of(PatternFlag.UTF8);
        if (requiresMatchOffset) {
            flags.add(PatternFlag.SOM_LEFTMOST);
        }
        if (VectorscanUtils.needsPrefilter(pattern)) {
            flags.add(PatternFlag.PREFILTER);
        }
        return flags;
    }
}
