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

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Resolves a native shared library on the classpath, building it from source when no prebuilt one
 * ships for the current platform.
 *
 * <p>For a library named {@code foo}, the expected layout is a prebuilt
 * {@code /native/<arch>/libfoo.<ext>} and a source {@code /native/foo.cpp}. Resolution tries, in
 * order: the prebuilt resource, a previously built copy in the cache, and finally a cmake build
 * (cmake must be on PATH). The cache matters because JMH forks a JVM per benchmark, so without it
 * every fork would rebuild.
 */
public final class NativeLibraryLoader {
    private NativeLibraryLoader() {}

    /**
     * @param libraryStem library name without the {@code lib} prefix or file extension
     * @return path to a loadable shared library for the current platform
     */
    public static Path resolve(String libraryStem) {
        try {
            String arch = architectureDirectory();
            String fileName = libraryFileName(libraryStem);

            URL prebuilt = NativeLibraryLoader.class.getResource("/native/" + arch + "/" + fileName);
            if (prebuilt != null) {
                return "file".equals(prebuilt.getProtocol())
                        ? Path.of(prebuilt.toURI())
                        : extractTo(prebuilt, Files.createTempFile(libraryStem, libraryExtension()));
            }

            Path cached = cacheDirectory(arch).resolve(fileName);
            if (Files.isRegularFile(cached)) {
                return cached;
            }
            return buildWithCmake(libraryStem, fileName, cached);
        } catch (Exception e) {
            throw new IllegalStateException("Could not resolve the " + libraryStem + " native library", e);
        }
    }

    private static String architectureDirectory() {
        String osArch = System.getProperty("os.arch");
        if (osArch == null) {
            throw new IllegalStateException("Could not detect os.arch for native library resolution");
        }
        return switch (osArch) {
            case "x86_64", "amd64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default ->
                throw new IllegalStateException(
                        "Unsupported architecture '%s' for the native library; supported: x86_64, amd64, aarch64, arm64"
                                .formatted(osArch));
        };
    }

    private static String libraryExtension() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) return ".dylib";
        if (os.contains("win")) return ".dll";
        return ".so";
    }

    /** cmake prefixes shared libraries with "lib" everywhere except Windows. */
    private static String libraryFileName(String libraryStem) {
        String extension = libraryExtension();
        return ".dll".equals(extension) ? libraryStem + extension : "lib" + libraryStem + extension;
    }

    private static Path cacheDirectory(String arch) throws IOException {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "regex-benchmark-native", arch);
        Files.createDirectories(dir);
        return dir;
    }

    private static Path extractTo(URL resource, Path target) throws IOException {
        try (InputStream in = resource.openStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        target.toFile().deleteOnExit();
        return target;
    }

    private static Path buildWithCmake(String libraryStem, String fileName, Path destination) throws Exception {
        String sourceResource = "/native/" + libraryStem + ".cpp";
        URL source = NativeLibraryLoader.class.getResource(sourceResource);
        if (source == null) {
            throw new IllegalStateException(
                    "No prebuilt library for this platform and no source at " + sourceResource + " to build one from");
        }

        Path projectDir = Files.createTempDirectory(libraryStem + "-cmake");
        Path buildDir = projectDir.resolve("build");
        Path outputDir = projectDir.resolve("out");
        Files.createDirectories(buildDir);
        Files.createDirectories(outputDir);

        extractTo(source, projectDir.resolve(libraryStem + ".cpp"));
        Files.writeString(
                projectDir.resolve("CMakeLists.txt"),
                """
                cmake_minimum_required(VERSION 3.16)
                project(%1$s CXX)
                set(CMAKE_CXX_STANDARD 17)
                set(CMAKE_CXX_STANDARD_REQUIRED ON)
                set(CMAKE_POSITION_INDEPENDENT_CODE ON)
                add_library(%1$s SHARED %1$s.cpp)
                set_target_properties(%1$s PROPERTIES
                    LIBRARY_OUTPUT_DIRECTORY "%2$s"
                    RUNTIME_OUTPUT_DIRECTORY "%2$s"
                )
                """.formatted(libraryStem, outputDir.toAbsolutePath().toString().replace('\\', '/')),
                StandardCharsets.UTF_8);

        run(projectDir, "cmake", "-S", projectDir.toString(), "-B", buildDir.toString(), "-DCMAKE_BUILD_TYPE=Release");
        run(projectDir, "cmake", "--build", buildDir.toString(), "--config", "Release");

        Path built = locateBuiltLibrary(outputDir, buildDir, fileName);
        Files.copy(built, destination, StandardCopyOption.REPLACE_EXISTING);
        return destination;
    }

    /** Multi-config generators ignore LIBRARY_OUTPUT_DIRECTORY, so fall back to searching the build tree. */
    private static Path locateBuiltLibrary(Path outputDir, Path buildDir, String fileName) throws IOException {
        for (Path root : List.of(outputDir, buildDir)) {
            try (Stream<Path> files = Files.walk(root)) {
                var hit = files.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().equals(fileName))
                        .findFirst();
                if (hit.isPresent()) {
                    return hit.get();
                }
            }
        }
        throw new IllegalStateException("cmake reported success but produced no " + fileName + " under " + buildDir);
    }

    private static void run(Path workingDir, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workingDir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException("%s failed:%n%s".formatted(String.join(" ", command), output));
        }
    }
}
