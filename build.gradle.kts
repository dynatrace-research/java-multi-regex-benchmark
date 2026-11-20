import java.io.File

plugins {
    java
    id("me.champeau.jmh") version "0.7.3"
    id("com.diffplug.spotless") version "8.3.0"
}

group = "com.dynatrace"
version = "1.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

repositories {
    mavenCentral()
}

val regexGenerator = "src/main/java/com/dynatrace/regex/utils/RegexGenerator.java"

spotless {
    java {
        palantirJavaFormat("2.90.0")
    }
    // Copyright headers: RegexGenerator was written by Esteban Pérez-Wohlfeil, everything else belongs to the
    // JKU/Dynatrace Co-Innovation Lab.
    format("licenseHeader") {
        target("src/**/*.java")
        targetExclude(regexGenerator)
        licenseHeaderFile("spotless/HEADER.java", "(package|import|public|class) ")
    }
    format("licenseHeaderPerezWohlfeil") {
        target(regexGenerator)
        licenseHeaderFile("spotless/HEADER_PEREZ_WOHLFEIL.java", "(package|import|public|class) ")
    }
}

dependencies {
    // Testing/benchmarking harness
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    implementation("org.openjdk.jmh:jmh-core:1.37")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    jmhAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")

    // Regex matching libraries
    implementation("com.dynatrace.vectorscan4j:vectorscan4j:0.6.0")
    implementation("com.gliwka.hyperscan:hyperscan:5.4.11-3.1.0")
    implementation("dk.brics:automaton:1.12-4")
    implementation("com.google.re2j:re2j:1.8")
    implementation("org.safere:safere:1.0.0")
    implementation("org.jruby.joni:joni:2.2.7")
    implementation("org.ahocorasick:ahocorasick:0.6.3")
    implementation("com.hankcs:aho-corasick-double-array-trie:1.2.3")
    implementation("com.datadoghq:reggie:0.5.0") // Reggie runtime API + bundled annotation processor

    // utils
    implementation("org.apache.commons:commons-csv:1.11.0")
    implementation("org.openjdk.jol:jol-core:0.17")
}

val jmhSmokeJvmArgs = listOf(
    "-Djol.magicFieldOffset=true",
    "--enable-native-access=ALL-UNNAMED",
    // JmhSmokeTest runs hundreds of sequential in-process JMH instances, and a timed-out run keeps
    // holding the global /tmp/jmh.lock (it can only be interrupted, not killed), which would fail
    // every later combination. JMH reads this into a static final at class-init, so it has to be a
    // JVM arg rather than System.setProperty.
    "-Djmh.ignoreLock=true",
)

tasks.test {
    useJUnitPlatform()
    dependsOn(tasks.named("jmhClasses"))
    classpath += sourceSets["jmh"].runtimeClasspath
    maxHeapSize = "1g"
    exclude("**/JmhSmokeTest*")
    jvmArgs("-Djol.magicFieldOffset=true", "--enable-native-access=ALL-UNNAMED")
}

// Manual run: ./gradlew engineCompatibility. Not part of `check`, since it runs every engine against
// every benchmark, which takes a long time.
tasks.register<Test>("engineCompatibility") {
    group = "verification"
    description = "Runs every engine against every benchmark and writes build/reports/engine-compat.*"
    useJUnitPlatform()
    dependsOn(tasks.named("jmhClasses"))
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath + sourceSets["jmh"].runtimeClasspath
    filter {
        includeTestsMatching("JmhSmokeTest")
    }
    maxHeapSize = "1g"
    // The matrix is printed while it runs; without this Gradle only shows stdout after the task ends.
    testLogging {
        showStandardStreams = true
    }
    jvmArgs(jmhSmokeJvmArgs)
    outputs.upToDateWhen { false }
}

jmh {
    resultFormat = "JSON"
    jvmArgsAppend = listOf("--enable-native-access=ALL-UNNAMED", "-Djol.magicFieldOffset=true")
}

// -Pargs="<JMH command line options>" overrides the JMH settings of the jmh task, e.g.
// ./gradlew jmh -Pargs="CountBenchmark.loghub -p engine=Re2,JavaRegex -wi 0". A token that does not start with '-' is a
// regular expression selecting benchmarks. The jmh plugin has no such option itself, so the options of the JMH command
// line (see `java -jar jmh.jar -h`) are translated here.
providers.gradleProperty("args").orNull?.takeIf { it.isNotBlank() }?.let { rawArgs ->
    val tokens = org.apache.tools.ant.types.Commandline.translateCommandline(rawArgs).toList()
    val includes = mutableListOf<String>()
    val excludes = mutableListOf<String>()
    val profilers = mutableListOf<String>()
    val warmupBenchmarks = mutableListOf<String>()
    var i = 0

    fun value(option: String): String {
        if (i + 1 >= tokens.size) throw GradleException("Missing value for $option in -Pargs")
        return tokens[++i]
    }

    fun bool(option: String): Boolean =
        when (val v = value(option)) {
            "true" -> true
            "false" -> false
            else -> throw GradleException("Expected true or false for $option in -Pargs, but got '$v'")
        }

    fun words(option: String): List<String> = org.apache.tools.ant.types.Commandline.translateCommandline(value(option)).toList()

    jmh {
        while (i < tokens.size) {
            when (val option = tokens[i]) {
                "-bm" -> benchmarkMode.set(value(option).split(","))
                "-bs" -> batchSize.set(value(option).toInt())
                "-e" -> excludes += value(option)
                "-f" -> fork.set(value(option).toInt())
                "-foe" -> failOnError.set(bool(option))
                "-gc" -> forceGC.set(bool(option))
                "-i" -> iterations.set(value(option).toInt())
                "-jvm" -> jvm.set(value(option))
                "-jvmArgs" -> jvmArgs.set(words(option))
                "-jvmArgsAppend" -> jvmArgsAppend.set(words(option))
                "-jvmArgsPrepend" -> jvmArgsPrepend.set(words(option))
                "-o" -> humanOutputFile.set(file(value(option)))
                "-opi" -> operationsPerInvocation.set(value(option).toInt())
                "-p" -> {
                    val assignment = value(option)
                    val eq = assignment.indexOf('=')
                    if (eq <= 0 || eq == assignment.length - 1) throw GradleException("Invalid -p value in -Pargs: $assignment")
                    benchmarkParameters.put(
                        assignment.substring(0, eq),
                        objects.listProperty<String>().value(assignment.substring(eq + 1).split(",")),
                    )
                }
                "-prof" -> profilers += value(option)
                "-r" -> timeOnIteration.set(value(option))
                "-rf" -> resultFormat.set(value(option))
                "-rff" -> resultsFile.set(file(value(option)))
                "-si" -> synchronizeIterations.set(bool(option))
                "-t" -> threads.set(value(option).toInt())
                "-tg" -> threadGroups.set(value(option).split(",").map { it.toInt() })
                "-to" -> jmhTimeout.set(value(option))
                "-tu" -> timeUnit.set(value(option))
                "-v" -> verbosity.set(value(option))
                "-w" -> warmup.set(value(option))
                "-wbs" -> warmupBatchSize.set(value(option).toInt())
                "-wf" -> warmupForks.set(value(option).toInt())
                "-wi" -> warmupIterations.set(value(option).toInt())
                "-wm" -> warmupMode.set(value(option))
                "-wmb" -> warmupBenchmarks += value(option)
                else -> {
                    if (option.startsWith("-")) throw GradleException("Unsupported JMH option in -Pargs: $option")
                    includes += option
                }
            }
            i++
        }
        if (includes.isNotEmpty()) this.includes.set(includes)
        if (excludes.isNotEmpty()) this.excludes.set(excludes)
        if (profilers.isNotEmpty()) this.profilers.set(profilers)
        if (warmupBenchmarks.isNotEmpty()) this.warmupBenchmarks.set(warmupBenchmarks)
    }
}

// Benchmarks append setup failures here; drop the previous run's log so errors don't accumulate.
tasks.named("jmh") {
    val errorLog = layout.buildDirectory.file("results/jmh/errors.csv").get().asFile
    doFirst { errorLog.delete() }
    // Always convert the results to CSV files afterwards, also when the benchmarks fail partway.
    finalizedBy("formatResults")
}

// Python environment for the result tooling (format_results.py, plot_results.py): a venv in build/.venv with the
// packages of requirements.txt. Needs python3 with the venv module on the PATH; re-runs when requirements.txt changes.
val venvDir = layout.buildDirectory.dir(".venv").get().asFile
val pythonExe = venvDir.resolve("bin/python").toString()
tasks.register<Exec>("setupPython") {
    group = "benchmark"
    description = "Creates build/.venv and installs requirements.txt"
    val requirements = file("requirements.txt")
    val stamp = venvDir.resolve(".requirements.stamp")
    inputs.file(requirements)
    outputs.file(stamp)
    commandLine(
        "bash", "-c",
        "python3 -m venv '$venvDir' && '$pythonExe' -m pip install --quiet -r '$requirements' && cp '$requirements' '$stamp'",
    )
}

// Generates the paper figures (PDF) and table bodies (LaTeX) with paper/icpe27/plot_results.py.
// -PresultsDir=<dir> selects the directory with the CSV files (default: build/results/jmh, the output of `jmh`),
// -PcompareDir=<dir> the CSV files of the same run on another architecture (adds the ARM64 vs. AMD64 comparison),
// -PfiguresDir=<dir> the output directory (default: build/results/figures).
fun registerFigures(name: String, description: String, defaultResults: String, defaultCompare: String? = null) =
    tasks.register<Exec>(name) {
        group = "benchmark"
        this.description = description
        dependsOn("setupPython")
        val results = findProperty("resultsDir")?.toString() ?: defaultResults
        val compare = findProperty("compareDir")?.toString() ?: defaultCompare
        val figures =
            findProperty("figuresDir")?.toString()
                ?: layout.buildDirectory.dir("results/figures").get().asFile.toString()
        val args = mutableListOf(pythonExe, "paper/icpe27/plot_results.py", "--data", results, "--out", figures)
        if (compare != null) args += listOf("--compare", compare)
        commandLine(args)
    }
registerFigures(
    "genFigures",
    "Generates the figures from build/results/jmh (or -PresultsDir)",
    layout.buildDirectory.dir("results/jmh").get().asFile.toString(),
)
registerFigures(
    "genPaperFigures",
    "Regenerates the figures of the paper from its committed results",
    "paper/icpe27/results/amd64",
    "paper/icpe27/results/arm64",
)

// Converts the JMH JSON results into one CSV file per benchmark class, next to the JSON file (requires python3 with
// pandas). Use -PresultsPath=<file> to convert another results file, e.g. `./gradlew formatResults -PresultsPath=...`.
// A global Gradle init script may already have registered a task with this name; that one is kept then.
if ("formatResults" !in tasks.names) {
    tasks.register<Exec>("formatResults") {
        group = "benchmark"
        description = "Converts the JMH JSON results to CSV files (one per benchmark class)"
        val resultsPath =
            findProperty("resultsPath")?.toString()
                ?: layout.buildDirectory.file("results/jmh/results.json").get().asFile.toString()
        dependsOn("setupPython")
        commandLine(pythonExe, "src/jmh/resources/format_results.py", resultsPath)
    }
}

// Fetches the Loghub datasets that LoghubWorkload reads into src/jmh/resources/loghub (not stored in git).
tasks.register<Exec>("downloadLoghub") {
    group = "benchmark"
    description = "Downloads the Loghub-2.0 datasets used by LoghubWorkload"
    commandLine("python3", "scripts/download_loghub.py")
    // No declared outputs on purpose: the script itself skips datasets that are present, which is cheap, and Gradle's
    // up-to-date check would not notice a dataset that was deleted by hand.
}

// The datasets are read from the classpath, so they have to be present whenever the jmh resources are packaged:
// this covers `jmh`, `jmhJar` (e.g. for remote runs) and the tests, which use the jmh runtime classpath.
tasks.named("processJmhResources") {
    dependsOn("downloadLoghub")
}
