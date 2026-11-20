# AGENTS.md

This file provides guidance to AI coding agents (e.g. Claude Code) when working with code in this repository.

For repo architecture, build/test commands, and how to run things, see [README.md](README.md).

## AI-generated files

Any file that is mostly AI-written with little manual editing must have a
disclaimer comment at the top stating that it is AI-generated, plus a description of what the file does and
where/how it is used (which command or code path invokes it).
Current list of files this applies to:
* `src/jmh/resources/format_results.py`
* `paper/icpe27/plot_results.py`
* `src/main/java/com/dynatrace/regex/engines/Joni.java`
* `src/main/java/com/dynatrace/regex/engines/DkBricsAutomaton.java`
* `src/test/java/RegexEngineTests.java`
