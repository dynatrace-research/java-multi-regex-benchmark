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
import dk.brics.automaton.*;
import java.util.ArrayList;
import java.util.List;

/**
 * An engine using {@code dk.brics.automaton}, with one automaton per pattern. Matching runs every automaton over the
 * input in turn, so a match always belongs to exactly one pattern.
 *
 * <p>The library has its own regex syntax, so the patterns (in {@code java.util.regex} syntax) are translated first, see
 * {@code Translator}. A pattern without an equivalent makes {@link #compile} throw an
 * {@link UnsupportedOperationException}. Three constructs are approximated, so their matches can differ from the other
 * engines: {@code \b} is dropped, a lookahead is matched as part of the match, and {@code ^X$} matches the whole input
 * (or line) once.
 *
 * <p>AI-generated: the translator, which is most of this class, was written by an AI coding agent (Claude Code) with
 * little manual editing. The engine is used through the {@code engine} parameter of the workloads, see
 * {@link RegexEngine#fromString}.
 */
public class DkBricsAutomaton implements RegexEngine {
    private final List<RunAutomaton> runAutomata = new ArrayList<>();
    /** For every pattern: whether it is {@code ^X$}, which has to match the whole input instead of being searched. */
    private final List<Boolean> wholeInput = new ArrayList<>();

    @Override
    public long compile(List<String> patterns, boolean requiresMatchOffset) {
        runAutomata.clear();
        wholeInput.clear();
        for (String pattern : patterns) {
            var translation = Translator.translate(pattern);
            runAutomata.add(new RunAutomaton(new RegExp(translation.bricsPattern()).toAutomaton()));
            wholeInput.add(translation.wholeInput());
        }
        return 0;
    }

    @Override
    public int count(BenchmarkState state, int idx) {
        String input = state.inputs.get(idx);
        int count = 0;
        for (int p = 0; p < runAutomata.size(); p++) {
            RunAutomaton runAutomaton = runAutomata.get(p);
            if (wholeInput.get(p)) {
                if (runAutomaton.run(input)) {
                    count++;
                }
                continue;
            }
            var matcher = runAutomaton.newMatcher(input);
            while (matcher.find()) {
                count++;
            }
        }
        return count;
    }

    @Override
    public int grep(BenchmarkState state, int idx) {
        return (int)
                state.inputs.get(idx).lines().filter(this::matchesAnyPattern).count();
    }

    private boolean matchesAnyPattern(String line) {
        for (int p = 0; p < runAutomata.size(); p++) {
            RunAutomaton runAutomaton = runAutomata.get(p);
            if (wholeInput.get(p)
                    ? runAutomaton.run(line)
                    : runAutomaton.newMatcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void getAllMatches(BenchmarkState state, int idx) {
        String input = state.inputs.get(idx);
        state.matches.clear();
        for (int p = 0; p < runAutomata.size(); p++) {
            if (wholeInput.get(p)) {
                if (!input.isEmpty() && runAutomata.get(p).run(input)) {
                    state.matches.add(p, 0, input.length());
                }
                continue;
            }
            var matcher = runAutomata.get(p).newMatcher(input);
            while (matcher.find()) {
                int start = matcher.start();
                int end = matcher.end();
                if (end > start) {
                    state.matches.add(p, start, end);
                }
            }
        }
    }

    /**
     * Translates {@code java.util.regex} syntax into the syntax of {@code dk.brics.automaton.RegExp}, so that the same
     * pattern strings can be given to every engine. The two syntaxes differ in a way that does not fail loudly: in brics a
     * backslash only escapes the next character ({@code \d} is the letter {@code d}), {@code &}, {@code @}, {@code #},
     * {@code ~}, {@code <} and {@code >} are operators, and there are no anchors, so passing a Java pattern through
     * unchanged compiles and then silently matches something else.
     *
     * <p>What is translated:
     *
     * <ul>
     *   <li>the classes {@code \d \D \w \W \s \S} (also inside {@code [...]}, except the negated ones), the escapes
     *       {@code \t \n \r \f \e}, hex, octal and unicode escapes, and escaped punctuation, and {@code .} (excluding line terminators like Java)
     *   <li>{@code (?:...)}, named groups and atomic groups {@code (?>...)} (atomicity only changes which match is
     *       chosen, not the language), and lazy and possessive quantifiers (dropped, for the same reason)
     *   <li>a leading {@code (?i)}: letters become {@code [xX]}, and letters in classes get both cases
     * </ul>
     *
     * <p>Approximations, which can change the matches (the caveats of the engine):
     *
     * <ul>
     *   <li>{@code \b} is dropped, so a match may also be found inside a longer word
     *   <li>a positive lookahead {@code (?=X)} is consumed like {@code (X)}, so the span of a match includes the lookahead
     *       text, and overlapping occurrences are not found. The number of matches is the same for non-overlapping text.
     *   <li>{@code ^X$} is matched against the whole input (or line) with {@code run}, giving a single match
     * </ul>
     *
     * <p>Everything else that is not a regular language or has no equivalent (backreferences, negative lookahead,
     * lookbehind, one-sided anchors, other flags, {@code \B}, {@code \Q..\E}, ...) throws
     * {@link UnsupportedOperationException}.
     */
    private static final class Translator {
        /** A translated pattern. {@code wholeInput} is set for {@code ^X$}, which has to match the entire input. */
        record Translation(String bricsPattern, boolean wholeInput) {}

        private static final String LINE_TERMINATORS = "\n\r\u0085" + (char) 0x2028 + (char) 0x2029;
        private static final String WHITESPACE = " \t\n\u000B\f\r";
        private static final String BRICS_OPERATORS = "|&?*+{}[]()^$.\"\\@#~<>-";
        private static final String BRICS_CLASS_SPECIALS = "[]^-\\";

        private final String pattern;
        private final StringBuilder out = new StringBuilder();
        private boolean ignoreCase;
        private int pos;
        private int end;
        private boolean afterQuantifier;

        private Translator(String pattern) {
            this.pattern = pattern;
        }

        static Translation translate(String pattern) {
            return new Translator(pattern).run();
        }

        private Translation run() {
            end = pattern.length();
            if (pattern.startsWith("(?i)")) {
                ignoreCase = true;
                pos = 4;
            }
            boolean anchoredStart = false;
            if (pattern.startsWith("^", pos)) {
                anchoredStart = true;
                pos++;
            }
            boolean anchoredEnd = false;
            if (end > pos && pattern.charAt(end - 1) == '$' && !endsWithEscape(end - 1)) {
                anchoredEnd = true;
                end--;
            }
            if (anchoredStart != anchoredEnd) {
                throw unsupported("an anchor on only one side");
            }
            while (pos < end) {
                char c = pattern.charAt(pos);
                boolean quantifier = false;
                switch (c) {
                    case '\\' -> escape();
                    case '[' -> characterClass();
                    case '(' -> group();
                    case ')', '|' -> {
                        out.append(c);
                        pos++;
                    }
                    case '*', '+', '?' -> {
                        // right after a quantifier, ? makes it lazy and + makes it possessive: same language
                        if (!afterQuantifier) {
                            out.append(c);
                            quantifier = true;
                        }
                        pos++;
                    }
                    case '{' -> quantifier = repetition();
                    case '.' -> {
                        out.append("[^").append(LINE_TERMINATORS).append(']');
                        pos++;
                    }
                    case '^', '$' -> throw unsupported("the anchor " + c + " inside a pattern");
                    default -> {
                        literal(c);
                        pos++;
                    }
                }
                afterQuantifier = quantifier;
            }
            return new Translation(out.toString(), anchoredStart);
        }

        private boolean endsWithEscape(int index) {
            int backslashes = 0;
            for (int i = index - 1; i >= pos && pattern.charAt(i) == '\\'; i--) {
                backslashes++;
            }
            return backslashes % 2 == 1;
        }

        /** {@code {n}}, {@code {n,}} or {@code {n,m}} are the same in both syntaxes; any other { is a literal. */
        private boolean repetition() {
            int close = pattern.indexOf('}', pos);
            if (close > 0 && close < end && pattern.substring(pos, close + 1).matches("\\{\\d+(,\\d*)?}")) {
                out.append(pattern, pos, close + 1);
                pos = close + 1;
                return true;
            }
            literal('{');
            pos++;
            return false;
        }

        private void group() {
            if (!pattern.startsWith("(?", pos)) {
                out.append('(');
                pos++;
                return;
            }
            if (pattern.startsWith("(?:", pos) || pattern.startsWith("(?>", pos) || pattern.startsWith("(?=", pos)) {
                out.append('(');
                pos += 3;
            } else if (pattern.startsWith("(?<", pos) && pos + 3 < end && Character.isLetter(pattern.charAt(pos + 3))) {
                int close = pattern.indexOf('>', pos);
                if (close < 0) {
                    throw unsupported("an unterminated group name");
                }
                out.append('(');
                pos = close + 1;
            } else {
                throw unsupported("the group construct " + pattern.substring(pos, Math.min(end, pos + 4)));
            }
        }

        private void escape() {
            if (pos + 1 >= pattern.length()) {
                throw unsupported("a trailing backslash");
            }
            char c = pattern.charAt(pos + 1);
            pos += 2;
            switch (c) {
                case 'd' -> out.append("[0-9]");
                case 'D' -> out.append("[^0-9]");
                case 'w' -> out.append("[a-zA-Z0-9_]");
                case 'W' -> out.append("[^a-zA-Z0-9_]");
                case 's' -> out.append('[').append(WHITESPACE).append(']');
                case 'S' -> out.append("[^").append(WHITESPACE).append(']');
                case 'b' -> {
                    // dropped, see the class comment
                }
                default -> literal(escapedChar(c));
            }
        }

        /** The character that an escape sequence stands for, after the backslash and the letter {@code c}. */
        private char escapedChar(char c) {
            switch (c) {
                case 't':
                    return '\t';
                case 'n':
                    return '\n';
                case 'r':
                    return '\r';
                case 'f':
                    return '\f';
                case 'e':
                    return '\u001B';
                case '0':
                    return octal();
                case 'x':
                    return hex(2);
                case 'u':
                    return hex(4);
                default:
                    if (Character.isLetterOrDigit(c)) {
                        throw unsupported("the escape \\" + c);
                    }
                    return c;
            }
        }

        /** The octal escape after {@code \0}: one to three octal digits (three only if the first is at most 3). */
        private char octal() {
            int value = 0;
            int digits = 0;
            while (pos < pattern.length() && digits < 3 && Character.digit(pattern.charAt(pos), 8) >= 0) {
                int next = value * 8 + Character.digit(pattern.charAt(pos), 8);
                if (next > 0377) {
                    break;
                }
                value = next;
                digits++;
                pos++;
            }
            if (digits == 0) {
                throw unsupported("an octal escape without digits");
            }
            return (char) value;
        }

        private char hex(int digits) {
            if (pos + digits > pattern.length()) {
                throw unsupported("a truncated hex escape");
            }
            char value = (char) Integer.parseInt(pattern.substring(pos, pos + digits), 16);
            pos += digits;
            return value;
        }

        private void literal(char c) {
            if (ignoreCase && Character.toLowerCase(c) != Character.toUpperCase(c)) {
                out.append('[')
                        .append(Character.toLowerCase(c))
                        .append(Character.toUpperCase(c))
                        .append(']');
            } else {
                if (BRICS_OPERATORS.indexOf(c) >= 0) {
                    out.append('\\');
                }
                out.append(c);
            }
        }

        private void characterClass() {
            out.append('[');
            pos++;
            if (pos < end && pattern.charAt(pos) == '^') {
                out.append('^');
                pos++;
            }
            while (true) {
                if (pos >= end) {
                    throw unsupported("an unterminated character class");
                }
                char c = pattern.charAt(pos);
                if (c == ']') {
                    pos++;
                    break;
                }
                if (c == '[' || (c == '&' && pattern.startsWith("&&", pos))) {
                    throw unsupported("a nested character class or an intersection");
                }
                if (c == '\\' && pos + 1 < end && "dDwWsS".indexOf(pattern.charAt(pos + 1)) >= 0) {
                    char shorthand = pattern.charAt(pos + 1);
                    if (Character.isUpperCase(shorthand)) {
                        throw unsupported("the negated class \\" + shorthand + " inside [...]");
                    }
                    out.append(
                            switch (shorthand) {
                                case 'd' -> "0-9";
                                case 'w' -> "a-zA-Z0-9_";
                                default -> WHITESPACE;
                            });
                    pos += 2;
                    continue;
                }
                char low = classChar();
                if (pos + 1 < end && pattern.charAt(pos) == '-' && pattern.charAt(pos + 1) != ']') {
                    pos++;
                    char high = classChar();
                    classRange(low, high);
                } else {
                    classRange(low, low);
                }
            }
            out.append(']');
        }

        /** Reads one (possibly escaped) character of a character class. */
        private char classChar() {
            char c = pattern.charAt(pos++);
            if (c != '\\') {
                return c;
            }
            if (pos >= end) {
                throw unsupported("a trailing backslash");
            }
            return escapedChar(pattern.charAt(pos++));
        }

        private void classRange(char low, char high) {
            appendClassRange(low, high);
            if (ignoreCase && Character.isLetter(low) && Character.isLetter(high)) {
                if (Character.isLowerCase(low) && Character.isLowerCase(high)) {
                    appendClassRange(Character.toUpperCase(low), Character.toUpperCase(high));
                } else if (Character.isUpperCase(low) && Character.isUpperCase(high)) {
                    appendClassRange(Character.toLowerCase(low), Character.toLowerCase(high));
                }
            }
        }

        private void appendClassRange(char low, char high) {
            classChar(low);
            if (high != low) {
                out.append('-');
                classChar(high);
            }
        }

        private void classChar(char c) {
            if (BRICS_CLASS_SPECIALS.indexOf(c) >= 0) {
                out.append('\\');
            }
            out.append(c);
        }

        private UnsupportedOperationException unsupported(String what) {
            return new UnsupportedOperationException(
                    "dk.brics.automaton has no equivalent of " + what + " in the pattern: " + pattern);
        }
    }
}
