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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

/**
 * A workload covering publicly available real log datasets (<a href="https://github.com/logpai/loghub-2.0/">link</a>).
 * The patterns are derived from the log templates of a dataset, and are matched against its real log lines. The inputs
 * are used as they are, not enlarged. The Gradle build downloads them to
 * {@code src/jmh/resources/loghub/} when they are missing ({@code scripts/download_loghub.py}).
 */
@State(Scope.Thread)
public class LoghubWorkload extends RegexEngineWorkload {
    @Param({"Apache", "Hadoop", "HealthApp", "HPC", "Linux", "Mac", "OpenSSH", "OpenStack", "Proxifier", "Zookeeper"})
    public String datasetName;

    /**
     * The approximate size of one chunk in characters. The log lines are split into
     * {@code ceil(total size / chunkSize)} chunks. Each benchmark invocation scans one chunk, and successive
     * invocations cycle through the chunks, so a slow engine does not have to scan the whole dataset per invocation,
     * and the time per invocation is about the same for every dataset. A line goes to chunk
     * {@code (line number) mod (number of chunks)}, so every chunk is a representative sample of the whole log. A
     * value of 0 scans the whole dataset per invocation.
     */
    @Param({"32768"})
    public int chunkSize;

    @Override
    protected void setupWorkload() {
        String logResourcePath = "loghub/%s/%s_full.log".formatted(datasetName, datasetName);
        String templatesResourcePath = "loghub/%s/%s_full.log_templates.csv".formatted(datasetName, datasetName);
        inputs = loadLogs(logResourcePath);
        splitIntoChunks();
        patterns = loadPatternsFromTemplates(templatesResourcePath);
    }

    /** Reorders {@link #inputs} chunk by chunk, putting line {@code j} in chunk {@code j mod (number of chunks)}. */
    private void splitIntoChunks() {
        long totalSize = inputs.stream().mapToLong(String::length).sum();
        int k = chunkSize <= 0 ? 1 : (int) Math.min(inputs.size(), (totalSize + chunkSize - 1) / chunkSize);
        if (k <= 1) {
            return;
        }
        List<String> reordered = new ArrayList<>(inputs.size());
        chunkStart = new int[k + 1];
        for (int c = 0; c < k; c++) {
            chunkStart[c] = reordered.size();
            for (int j = c; j < inputs.size(); j += k) {
                reordered.add(inputs.get(j));
            }
        }
        chunkStart[k] = reordered.size();
        inputs = reordered;
    }

    static List<String> loadPatternsFromTemplates(String resourcePath) {
        try (var reader = openResourceReader(resourcePath);
                CSVParser parser = CSVFormat.DEFAULT
                        .builder()
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .build()
                        .parse(reader)) {
            String templateColumn = findHeaderName(parser, "EventTemplate", resourcePath);
            List<String> loadedPatterns = new ArrayList<>();
            for (CSVRecord record : parser) {
                String template = record.get(templateColumn);
                loadedPatterns.add(templateToRegex(template));
            }
            return loadedPatterns;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load templates from resource: " + resourcePath, e);
        }
    }

    private static List<String> loadLogs(String resourcePath) {
        try (BufferedReader reader = openResourceReader(resourcePath)) {
            return new ArrayList<>(reader.lines().toList());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load resource: " + resourcePath, e);
        }
    }

    private static BufferedReader openResourceReader(String resourcePath) {
        InputStream stream = LoghubWorkload.class.getClassLoader().getResourceAsStream(resourcePath);
        Objects.requireNonNull(stream, "Resource not found: " + resourcePath);
        return new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }

    private static String findHeaderName(CSVParser parser, String columnName, String resourcePath) {
        for (String header : parser.getHeaderMap().keySet()) {
            if (stripBom(header).equals(columnName)) {
                return header;
            }
        }
        throw new IllegalStateException("Column '%s' not found in resource: %s".formatted(columnName, resourcePath));
    }

    private static String stripBom(String value) {
        if (!value.isEmpty() && value.charAt(0) == '\uFEFF') {
            return value.substring(1);
        }
        return value;
    }

    private static String templateToRegex(String template) {
        // We want to escape every special character, except for <*>, which should be treated as .*
        String specialChars = "|&?*+{}~[]^-.#@\"<>()$";
        for (char ch : specialChars.toCharArray()) {
            template = template.replace(String.valueOf(ch), String.format("\\%c", +ch));
        }
        // after masking the template, <*> has become \<\*\>, so we swap that for .*.
        template = template.replace("\\<\\*\\>", ".*");
        while (template.endsWith(".*")) {
            template = template.substring(0, template.length() - 2);
        }
        return template;
    }
}
