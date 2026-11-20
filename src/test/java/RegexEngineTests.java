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
import static org.junit.jupiter.api.Assertions.*;

import com.dynatrace.regex.engines.RegexEngine;
import com.dynatrace.regex.utils.MatchBuffer;
import com.dynatrace.regex.workloads.BenchmarkState;
import com.dynatrace.regex.workloads.LoghubWorkload;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;

/**
 * Tests of the regex engines: a table of feature probes that every engine is checked against (and that also writes the
 * feature support tables to {@code build/reports}), the dk.brics.automaton translation, and the Loghub match counts.
 * Run by {@code ./gradlew test}.
 *
 * <p>AI-generated: the feature-support and dk.brics.automaton tests were written by an AI coding agent (Claude Code)
 * with little manual editing.
 */
public class RegexEngineTests {
    /** All engines under test. HyperscanJava is left out, because it cannot share a JVM with vectorscan4j. */
    private static final List<String> ALL_ENGINES = List.of(
            "JavaRegex",
            "Re2",
            "SafeRe",
            "Joni",
            "Reggie",
            "Vectorscan",
            "VectorscanBatched",
            "DkBricsAutomaton",
            "AhoCorasick",
            "AhoCorasick2");

    /** Engines that treat every pattern as a literal keyword, so regex semantics do not apply to them. */
    private static final List<String> LITERAL_ONLY_ENGINES = List.of("AhoCorasick", "AhoCorasick2");

    private static final List<String> REGEX_ENGINES = ALL_ENGINES.stream()
            .filter(engine -> !LITERAL_ONLY_ENGINES.contains(engine))
            .toList();

    @Test
    void comparesOverlappingMatches() {
        Map<String, Integer> expectedMatches = Map.of(
                "JavaRegex", 2,
                "Re2", 2,
                "Vectorscan", 3,
                "DkBricsAutomaton", 2);

        for (String engineName : expectedMatches.keySet()) {
            RegexEngine engine = RegexEngine.fromString(engineName);
            try {
                engine.compile(List.of("aa"));
                assertEquals(expectedMatches.get(engineName), engine.count(stateOf("aaaa"), 0), engineName);
            } finally {
                engine.close();
            }
        }
    }

    /** The spans in the buffer as "start-end" strings, for comparing them in tests. */
    private static List<String> spansOf(MatchBuffer matches) {
        List<String> spans = new ArrayList<>();
        for (int i = 0; i < matches.size(); i++) {
            spans.add(matches.start(i) + "-" + matches.end(i));
        }
        return spans;
    }

    @Test
    void runsGetAllMatchesWithGeneratedRegexes() {
        List<String> engineNames = REGEX_ENGINES;
        String input = sentenceFromRegexGenerator();
        List<String> generatedPatterns = generateRegexesFromRegexGenerator(300);
        List<String> patterns = new ArrayList<>();
        for (String pattern : generatedPatterns) {
            if (matchesInputInJava(pattern, input) && compilesOnAllEngines(pattern, engineNames)) {
                patterns.add(pattern);
            }
            if (patterns.size() == 8) {
                break;
            }
        }

        assertFalse(patterns.isEmpty(), "Could not find generated regexes that match the input across engines.");
        assertTrue(getAllMatches("JavaRegex", patterns, input).size() > 0, "Expected at least one Java regex match.");

        for (String engineName : engineNames) {
            assertNotNull(getAllMatches(engineName, patterns, input), engineName);
        }
    }

    /** The patterns of SingleRegexWorkload (and some more syntax) that dk.brics can express exactly after translation. */
    @Test
    void dkBricsCountsLikeJavaRegexForTranslatedSyntax() {
        String text = "xx abc 2026-10-12T10:11:12.123Z foo ERROR error Error john.doe@example.com https://a.org/p?q "
                + "192.168.1.1 123456789 aaaab xxxxxxxxxxy 123e4567-e89b-12d3-a456-426614174000 z42 a&b a.b {x} "
                + "tab\there start mid end";
        List<String> patterns = List.of(
                "(?i)error",
                "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}",
                "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2})?",
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                "https?://[\\w./-]+",
                "x{1,50}y",
                "\\d{3,10}",
                "[a-z]z\\d+",
                "\\w+",
                "\\S+",
                ".*mid",
                "start.*end",
                "(?>a+)b",
                "(a+?)b",
                "(a|aa)*c",
                "a&b",
                "\\{x}",
                "[^a-z ]+",
                "(?i)[a-c]+",
                "(?<word>foo)\\s",
                "\\x61\\u0062c",
                "a\\046b",
                "tab\\there");
        for (String pattern : patterns) {
            RegexEngine dk = RegexEngine.fromString("DkBricsAutomaton");
            RegexEngine java = RegexEngine.fromString("JavaRegex");
            dk.compile(List.of(pattern));
            java.compile(List.of(pattern));
            assertEquals(java.count(stateOf(text), 0), dk.count(stateOf(text), 0), pattern);
            dk.close();
            java.close();
        }
    }

    /** The three constructs that are approximated (see DkBricsAutomaton): the match counts are the documented ones. */
    @Test
    void dkBricsApproximatesWordBoundaryLookaheadAndAnchors() {
        RegexEngine dk = RegexEngine.fromString("DkBricsAutomaton");
        dk.compile(List.of("\\bcat\\b"));
        // the boundaries are dropped, so "cat" is also found inside "concatenate"
        assertEquals(2, dk.count(stateOf("a cat concatenate"), 0));

        dk.compile(List.of("foo(?=bar)"));
        assertEquals(1, dk.count(stateOf("foobar foobaz"), 0));
        MatchBuffer matches = getAllMatches("DkBricsAutomaton", List.of("foo(?=bar)"), "xfoobar");
        // the span includes the lookahead text
        assertEquals(1, matches.start(0));
        assertEquals(7, matches.end(0));

        dk.compile(List.of("^\\d+$"));
        assertEquals(1, dk.count(stateOf("12345"), 0));
        assertEquals(0, dk.count(stateOf("123a45"), 0));
        dk.close();
    }

    @Test
    void dkBricsRejectsSyntaxWithoutEquivalent() {
        for (String pattern :
                List.of("(\\w+) \\1", "foo(?!bar)", "(?<=a)b", "^abc", "abc$", "(?s)a", "\\Bfoo", "a^b")) {
            RegexEngine dk = RegexEngine.fromString("DkBricsAutomaton");
            assertThrows(UnsupportedOperationException.class, () -> dk.compile(List.of(pattern)), pattern);
            dk.close();
        }
    }

    @Test
    public void correctNumMatchesForLoghub() {
        List<String> engineNames = List.of("DkBricsAutomaton", "Joni");
        // Mirrors LoghubWorkload's @Param datasetName list; "Mac" is excluded there because vectorscan can't
        // compile every pattern in that dataset.
        List<String> datasetNames = List.of("Apache");

        for (String datasetName : datasetNames) {
            List<String> logLines = loadLoghubLogLines(datasetName);
            List<LoghubTemplate> templates = loadLoghubTemplates(datasetName);
            IO.println(String.format("dataset = %s\tSample line = %s", datasetName, logLines.getFirst()));
            for (String engineName : engineNames) {
                IO.println("engine = " + engineName);
                for (LoghubTemplate template : templates) {
                    RegexEngine engine = RegexEngine.fromString(engineName);
                    try {
                        engine.compile(List.of(template.regex()));
                        int actualMatches = countMatchesAcrossLines(engine, logLines);
                        IO.println(String.format(
                                "%s\t/\t%s\t/\t%s\t/\t%s\t/\t%s\t/\t\"%s\"",
                                engineName,
                                datasetName,
                                template.eventId,
                                template.occurrences(),
                                actualMatches,
                                template.regex()));
                        assertEquals(
                                template.occurrences(),
                                actualMatches,
                                "%s / %s / %s".formatted(engineName, datasetName, template.eventId()));
                    } finally {
                        engine.close();
                    }
                }
            }
        }
    }

    private static int countMatchesAcrossLines(RegexEngine engine, List<String> lines) {
        BenchmarkState state = stateOf(lines.toArray(String[]::new));
        int total = 0;
        for (int idx = 0; idx < lines.size(); idx++) {
            total += engine.count(state, idx);
        }
        return total;
    }

    private record LoghubTemplate(String eventId, String regex, int occurrences) {}

    /** Loads the {@code EventTemplate}/{@code Occurrences} columns from a Loghub {@code *_templates.csv} file,
     * converting each template into the regex produced by {@link LoghubWorkload}'s (private) templateToRegex. */
    private static List<LoghubTemplate> loadLoghubTemplates(String datasetName) {
        String resourcePath = "loghub/%s/%s_full.log_templates.csv".formatted(datasetName, datasetName);
        try (InputStream stream = RegexEngineTests.class.getClassLoader().getResourceAsStream(resourcePath)) {
            Objects.requireNonNull(stream, "Resource not found: " + resourcePath);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
                    CSVParser parser = CSVFormat.DEFAULT
                            .builder()
                            .setHeader()
                            .setSkipHeaderRecord(true)
                            .build()
                            .parse(reader)) {
                List<LoghubTemplate> templates = new ArrayList<>();
                for (CSVRecord record : parser) {
                    String eventId = record.get("EventId");
                    String template = record.get("EventTemplate");
                    int occurrences = Integer.parseInt(record.get("Occurrences").trim());
                    templates.add(new LoghubTemplate(eventId, templateToRegex(template), occurrences));
                }
                return templates;
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load templates from resource: " + resourcePath, e);
        }
    }

    private static List<String> loadLoghubLogLines(String datasetName) {
        String resourcePath = "loghub/%s/%s_full.log".formatted(datasetName, datasetName);
        try (InputStream stream = RegexEngineTests.class.getClassLoader().getResourceAsStream(resourcePath)) {
            Objects.requireNonNull(stream, "Resource not found: " + resourcePath);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                return reader.lines().toList();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load resource: " + resourcePath, e);
        }
    }

    /** Invokes LoghubWorkload's private templateToRegex(String) via reflection so this test exercises the exact
     * conversion logic used by the real benchmark, rather than a re-implementation that could drift out of sync. */
    private static String templateToRegex(String template) {
        try {
            Method method = LoghubWorkload.class.getDeclaredMethod("templateToRegex", String.class);
            method.setAccessible(true);
            return (String) method.invoke(null, template);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Could not call LoghubWorkload.templateToRegex", e);
        }
    }

    /** The feature probes of the support table, which {@link #enginesMatchJavaExceptForKnownGaps} also asserts. */
    private static final List<FeatureProbe> PROBES = List.of(
            // Basic regex features
            new FeatureProbe(Category.BASIC, "literal characters", "abc", "xabc abc"),
            new FeatureProbe(Category.BASIC, "quantifier *", "ab*c", "ac abc abbc adc"),
            new FeatureProbe(Category.BASIC, "quantifier +", "ab+c", "ac abc abbc"),
            new FeatureProbe(Category.BASIC, "quantifier ?", "colou?r", "color colour colouur"),
            new FeatureProbe(Category.BASIC, "bounded repeat {m,n}", "a{2,3}b", "ab aab aaab"),
            new FeatureProbe(Category.BASIC, "alternation |", "cat|dog", "cat dog cow"),
            new FeatureProbe(Category.BASIC, "capture group ()", "(Hello|World)!", "Hello! World! Goodbye!"),
            new FeatureProbe(Category.BASIC, "non-capturing group (?:)", "(?:ab)+c", "abc ababc ac"),
            new FeatureProbe(Category.BASIC, "character class []", "[A-C][0-9]", "A1 B2 C3 D4 A"),
            new FeatureProbe(Category.BASIC, "negated class [^]", "[^x]x", "axbxxxyx"),
            new FeatureProbe(Category.BASIC, "dot .", "x.y", "xay x-y xy"),
            new FeatureProbe(Category.BASIC, "shorthand \\d \\s \\w", "\\d\\s\\w", "1 a 2 b"),
            new FeatureProbe(Category.BASIC, "start anchor ^", "^ab", "ab ab"),
            new FeatureProbe(Category.BASIC, "end anchor $", "ab$", "ab ab"),
            // Advanced regex features
            new FeatureProbe(Category.ADVANCED, "quoted literal \\Q..\\E", "\\Qa.b\\E", "xx a.b yy"),
            new FeatureProbe(Category.ADVANCED, "word boundary \\b", "\\bcat\\b", "cat concat cat"),
            new FeatureProbe(Category.ADVANCED, "case-insensitive (?i)", "(?i)cat", "Cat CAT cow"),
            new FeatureProbe(Category.ADVANCED, "dotall (?s)", "(?s)a.b", "a\nb"),
            new FeatureProbe(Category.ADVANCED, "lazy quantifier *?", "a.*?b", "aXbaYb"),
            new FeatureProbe(Category.ADVANCED, "possessive quantifier *+", "a++b", "aab ab"),
            new FeatureProbe(Category.ADVANCED, "named group (?<n>)", "(?<n>\\d)x", "1x 2y 3x"),
            new FeatureProbe(Category.ADVANCED, "backreference \\1", "(\\w)\\1", "aabbcd"),
            new FeatureProbe(Category.ADVANCED, "atomic group (?>)", "(?>a+)a", "aaa"),
            new FeatureProbe(Category.ADVANCED, "lookahead (?=)", "\\d(?=\\d)", "123"),
            new FeatureProbe(Category.ADVANCED, "negative lookahead (?!)", "foo(?!bar)", "foobar foobaz"),
            new FeatureProbe(Category.ADVANCED, "lookbehind (?<=)", "(?<=a)b", "ab cb ab"),
            // Unicode support. Written with escapes so that the source file's encoding does not matter.
            // é is e with acute, 😀 is an emoji outside the basic multilingual plane.
            new FeatureProbe(Category.UNICODE, "non-ASCII literal", "é", "café é"),
            new FeatureProbe(Category.UNICODE, "literal outside the BMP", "😀", "x😀y😀"),
            new FeatureProbe(Category.UNICODE, "dot matches a whole code point", "a.b", "a😀b"),
            new FeatureProbe(Category.UNICODE, "non-ASCII range [а-я]", "[а-я]+", "при hi"),
            new FeatureProbe(Category.UNICODE, "property class \\p{L}", "\\p{L}+", "naïve façade"),
            new FeatureProbe(Category.UNICODE, "unicode case-insensitive (?iu)", "(?iu)é", "É é"));

    /** The probes that the literal-only engines pass: they search for the pattern as plain text. */
    private static final Set<String> LITERAL_PROBES =
            Set.of("literal characters", "non-ASCII literal", "literal outside the BMP");

    /**
     * Per engine, the features of {@link #PROBES} for which its count differs from java.util.regex's, or that it cannot
     * compile (see the "missing" column of the feature support table). Every other probe must give java.util.regex's
     * count. The literal-only engines are checked against {@link #LITERAL_PROBES} instead.
     */
    private static final Map<String, Set<String>> KNOWN_GAPS = Map.of(
            "Re2",
            Set.of(
                    "possessive quantifier *+",
                    "backreference \\1",
                    "atomic group (?>)",
                    "lookahead (?=)",
                    "negative lookahead (?!)",
                    "lookbehind (?<=)",
                    "unicode case-insensitive (?iu)"),
            "SafeRe",
            Set.of(
                    "possessive quantifier *+",
                    "backreference \\1",
                    "atomic group (?>)",
                    "lookahead (?=)",
                    "negative lookahead (?!)",
                    "lookbehind (?<=)"),
            "Reggie",
            Set.of("dot matches a whole code point", "unicode case-insensitive (?iu)"),
            "Vectorscan",
            Set.of(
                    "possessive quantifier *+",
                    "backreference \\1",
                    "atomic group (?>)",
                    "lookahead (?=)",
                    "negative lookahead (?!)",
                    "lookbehind (?<=)",
                    "non-ASCII range [а-я]",
                    "property class \\p{L}",
                    "unicode case-insensitive (?iu)"),
            "DkBricsAutomaton",
            Set.of(
                    "start anchor ^",
                    "end anchor $",
                    "quoted literal \\Q..\\E",
                    "word boundary \\b",
                    "dotall (?s)",
                    "lazy quantifier *?",
                    "backreference \\1",
                    "atomic group (?>)",
                    "lookahead (?=)",
                    "negative lookahead (?!)",
                    "lookbehind (?<=)",
                    "dot matches a whole code point",
                    "property class \\p{L}",
                    "unicode case-insensitive (?iu)"));

    /** The gaps of an engine; VectorscanBatched compiles the patterns like Vectorscan. */
    private static Set<String> knownGaps(String engineName) {
        return KNOWN_GAPS.getOrDefault(engineName.equals("VectorscanBatched") ? "Vectorscan" : engineName, Set.of());
    }

    /**
     * Asserts the feature support: on every probe that is not one of the engine's known gaps, the engine counts like
     * java.util.regex, and on every known gap it does not (so an engine that gains a feature fails here, and its entry
     * has to be removed). Replaces separate tests per feature (alternation, groups, lookarounds, ...).
     */
    @Test
    void enginesMatchJavaExceptForKnownGaps() {
        for (String engineName : ALL_ENGINES.subList(1, ALL_ENGINES.size())) {
            boolean literalOnly = LITERAL_ONLY_ENGINES.contains(engineName);
            for (FeatureProbe probe : PROBES) {
                Integer expected = countOrNull("JavaRegex", probe);
                boolean passes = expected.equals(countOrNull(engineName, probe));
                String message = engineName + " / " + probe.feature();
                if (literalOnly) {
                    // Some regex probes are passed by chance (e.g. no match on both sides), so only assert the
                    // literals.
                    if (LITERAL_PROBES.contains(probe.feature())) {
                        assertTrue(passes, message);
                    }
                } else if (knownGaps(engineName).contains(probe.feature())) {
                    assertFalse(passes, message + " now works, remove it from KNOWN_GAPS");
                } else {
                    assertTrue(passes, message);
                }
            }
        }
    }

    /**
     * Writes three tables to build/reports. feature-support.csv has one row per probe (engines as columns),
     * feature-support-summary.csv has one row per engine and one column per {@link Category}, and
     * feature-support-missing.csv lists, per engine and category, the features that the engine does not support.
     *
     * <p>Nothing is asserted. For the basic, advanced and unicode categories a probe is passed ("yes") when the
     * engine's match count equals java.util.regex's, failed ("no") when it differs, and "error" when the engine throws.
     * A summary cell is the number of passed probes of the category. These probes are chosen so that matches do not
     * overlap, because some engines (e.g. Vectorscan) report overlapping matches and would otherwise be marked "no" for
     * that reason alone.
     *
     * <p>The matching-semantics cell is not a score but a label, see {@link #matchingSemantics}.
     */
    @Test
    void writesFeatureSupportTable() throws IOException {
        List<String> engineNames = ALL_ENGINES;
        List<FeatureProbe> probes = PROBES;

        List<String> engineColumns = engineNames.subList(1, engineNames.size());
        List<String> header = new ArrayList<>(List.of("category", "feature", "pattern", "input", "java"));
        header.addAll(engineColumns);

        // engine -> category -> number of passed probes
        Map<String, Map<Category, Integer>> passed = new LinkedHashMap<>();
        // engine -> category -> the features of the probes that were not passed, each with the way it failed
        Map<String, Map<Category, List<String>>> missing = new LinkedHashMap<>();
        for (String engineName : engineColumns) {
            passed.put(engineName, new EnumMap<>(Category.class));
            missing.put(engineName, new EnumMap<>(Category.class));
        }

        Path csvPath = Path.of("build", "reports", "feature-support.csv");
        Files.createDirectories(csvPath.getParent());
        try (CSVPrinter printer = new CSVPrinter(
                Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8),
                CSVFormat.DEFAULT
                        .builder()
                        .setHeader(header.toArray(String[]::new))
                        .build())) {
            for (FeatureProbe probe : probes) {
                Integer expected = countOrNull("JavaRegex", probe);
                List<String> cells = new ArrayList<>();
                cells.add(probe.category().title);
                cells.add(probe.feature());
                cells.add(probe.pattern());
                // Newlines are escaped so that each probe stays on one line of the file.
                cells.add(probe.input().replace("\n", "\\n"));
                cells.add(String.valueOf(expected));
                for (String engineName : engineColumns) {
                    Integer actual = countOrNull(engineName, probe);
                    boolean pass = actual != null && actual.equals(expected);
                    cells.add(actual == null ? "error" : pass ? "yes" : "no");
                    if (pass) {
                        passed.get(engineName).merge(probe.category(), 1, Integer::sum);
                    } else {
                        missing.get(engineName)
                                .computeIfAbsent(probe.category(), category -> new ArrayList<>())
                                .add(probe.feature() + (actual == null ? " (error)" : " (differs)"));
                    }
                }
                printer.printRecord(cells);
            }
            // The matching semantics are reported as a label per engine instead of a pass/fail.
            List<String> semanticsRow = new ArrayList<>(List.of(
                    Category.SEMANTICS.title,
                    "which match is reported",
                    SEMANTICS_PATTERN,
                    SEMANTICS_INPUT,
                    matchingSemantics("JavaRegex")));
            for (String engineName : engineColumns) {
                semanticsRow.add(matchingSemantics(engineName));
            }
            printer.printRecord(semanticsRow);
        }

        List<String> summaryHeader = new ArrayList<>(List.of("engine"));
        for (Category category : Category.values()) {
            summaryHeader.add(category.title);
        }
        Path summaryPath = Path.of("build", "reports", "feature-support-summary.csv");
        try (CSVPrinter printer = new CSVPrinter(
                Files.newBufferedWriter(summaryPath, StandardCharsets.UTF_8),
                CSVFormat.DEFAULT
                        .builder()
                        .setHeader(summaryHeader.toArray(String[]::new))
                        .build())) {
            for (String engineName : engineColumns) {
                List<String> cells = new ArrayList<>(List.of(engineName));
                for (Category category : Category.values()) {
                    if (category == Category.SEMANTICS) {
                        cells.add(matchingSemantics(engineName));
                        continue;
                    }
                    long total = probes.stream()
                            .filter(probe -> probe.category() == category)
                            .count();
                    cells.add(passed.get(engineName).getOrDefault(category, 0) + "/" + total);
                }
                printer.printRecord(cells);
            }
        }

        // The features each engine is missing. The matching semantics are not a feature, so that category is left out.
        List<Category> featureCategories = Arrays.stream(Category.values())
                .filter(c -> c != Category.SEMANTICS)
                .toList();
        List<String> missingHeader = new ArrayList<>(List.of("engine"));
        for (Category category : featureCategories) {
            missingHeader.add(category.title);
        }
        Path missingPath = Path.of("build", "reports", "feature-support-missing.csv");
        try (CSVPrinter printer = new CSVPrinter(
                Files.newBufferedWriter(missingPath, StandardCharsets.UTF_8),
                CSVFormat.DEFAULT
                        .builder()
                        .setHeader(missingHeader.toArray(String[]::new))
                        .build())) {
            for (String engineName : engineColumns) {
                List<String> cells = new ArrayList<>(List.of(engineName));
                for (Category category : featureCategories) {
                    cells.add(String.join("; ", missing.get(engineName).getOrDefault(category, List.of())));
                }
                printer.printRecord(cells);
            }
        }
    }

    private static final String SEMANTICS_PATTERN = "a|ab";
    private static final String SEMANTICS_INPUT = "ab";

    /**
     * Tells which matches the engine reports for {@code a|ab} on {@code ab}, which differ between the strategies:
     * <ul>
     *   <li>"backtracking": the first alternative that matches wins, so {@code [0,1)} (what a backtracking engine such as
     *       java.util.regex reports; RE2 gives the same result without backtracking)
     *   <li>"leftmost-longest": the longest match at the leftmost start, so {@code [0,2)}
     *   <li>"all matches": every match end is reported, so both {@code [0,1)} and {@code [0,2)}
     * </ul>
     * Anything else is "other", and an engine that throws is "error". Engines that only search for literal keywords
     * would read the probe as the literal text {@code a|ab}, so they are not classified and get "n/a (literals only)".
     */
    private static String matchingSemantics(String engineName) {
        if (LITERAL_ONLY_ENGINES.contains(engineName)) {
            return "n/a (literals only)";
        }
        List<String> spans;
        try {
            spans = spansOf(getAllMatches(engineName, List.of(SEMANTICS_PATTERN), SEMANTICS_INPUT));
        } catch (RuntimeException | LinkageError e) {
            return "error";
        }
        String first = "0-1";
        String longest = "0-2";
        if (spans.equals(List.of(first))) return "backtracking";
        if (spans.equals(List.of(longest))) return "leftmost-longest";
        if (spans.equals(List.of(first, longest))) return "all matches";
        return "other";
    }

    /** Returns the engine's count on the probe's input, or null if it throws while compiling or counting. */
    private static Integer countOrNull(String engineName, FeatureProbe probe) {
        RegexEngine engine = RegexEngine.fromString(engineName);
        try {
            engine.compile(List.of(probe.pattern()));
            return engine.count(stateOf(probe.input()), 0);
        } catch (RuntimeException | LinkageError e) {
            return null;
        } finally {
            engine.close();
        }
    }

    /** The columns of the summary table. */
    private enum Category {
        BASIC("Basic regex features"),
        ADVANCED("Advanced regex features"),
        SEMANTICS("Matching semantics"),
        UNICODE("Unicode support");

        private final String title;

        Category(String title) {
            this.title = title;
        }
    }

    private record FeatureProbe(Category category, String feature, String pattern, String input) {}

    private static boolean matchesInputInJava(String pattern, String input) {
        try {
            return Pattern.compile(pattern).matcher(input).find();
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    private static boolean compilesOnAllEngines(String pattern, List<String> engineNames) {
        for (String engineName : engineNames) {
            RegexEngine engine = RegexEngine.fromString(engineName);
            try {
                engine.compile(List.of(pattern));
            } catch (RuntimeException e) {
                return false;
            } finally {
                engine.close();
            }
        }
        return true;
    }

    private static String sentenceFromRegexGenerator() {
        try {
            Class<?> generatorClass = Class.forName("com.dynatrace.regex.utils.RegexGenerator");
            return (String) generatorClass.getField("SENTENCE").get(null);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Could not access RegexGenerator.SENTENCE", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> generateRegexesFromRegexGenerator(int n) {
        try {
            Class<?> generatorClass = Class.forName("com.dynatrace.regex.utils.RegexGenerator");
            return (List<String>)
                    generatorClass.getMethod("generateRandomRegexes", int.class).invoke(null, n);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Could not call RegexGenerator.generateRandomRegexes", e);
        }
    }

    @Test
    void getAllMatchesReportsPatternIds() {
        for (String engineName : ALL_ENGINES) {
            RegexEngine engine = RegexEngine.fromString(engineName);
            try {
                engine.compile(List.of("foo", "bar"));
                BenchmarkState state = stateOf("xbarxfoo");
                engine.getAllMatches(state, 0);
                assertEquals(2, state.matches.size(), engineName);
                for (int i = 0; i < state.matches.size(); i++) {
                    int expectedId = state.matches.start(i) == 1 ? 1 : 0;
                    assertEquals(expectedId, state.matches.patternId(i), engineName);
                }
            } finally {
                engine.close();
            }
        }
    }

    private static MatchBuffer getAllMatches(String engineName, List<String> patterns, String input) {
        RegexEngine engine = RegexEngine.fromString(engineName);
        try {
            engine.compile(patterns);
            BenchmarkState state = stateOf(input);
            engine.getAllMatches(state, 0);
            return state.matches;
        } finally {
            engine.close();
        }
    }

    /**
     * A BenchmarkState that only carries the given inputs and their UTF-8 copies. The tests create and compile the
     * engine themselves, so nothing else of the state is set up.
     */
    private static BenchmarkState stateOf(String... texts) {
        return new BenchmarkState() {
            {
                inputs = List.of(texts);
                encodeInputs();
            }

            @Override
            protected String getEngineName() {
                throw new UnsupportedOperationException();
            }

            @Override
            protected void setupWorkload() {}
        };
    }
}
