# Third-party notices

This repository is licensed under the Apache License 2.0 (see `LICENSE`). It uses the third-party software and data
listed below. The libraries are not copied into this repository: Gradle downloads them from Maven Central at build
time, and each is subject to its own license.

## Regex engines and libraries

| Library | Maven coordinates | License |
|---|---|---|
| vectorscan4j (bundles Vectorscan) | `com.dynatrace.vectorscan4j:vectorscan4j` | Apache 2.0 (Vectorscan: BSD-3-Clause) |
| hyperscan-java (bundles Intel Hyperscan) | `com.gliwka.hyperscan:hyperscan` | BSD-3-Clause |
| dk.brics.automaton | `dk.brics:automaton` | BSD |
| RE2/J | `com.google.re2j:re2j` | Go license (BSD-style) |
| SafeRE | `org.safere:safere` | BSD-3-Clause |
| Joni (and its dependency JCodings) | `org.jruby.joni:joni`, `org.jruby.jcodings:jcodings` | MIT |
| Reggie | `com.datadoghq:reggie` | Apache 2.0 |
| Aho-Corasick | `org.ahocorasick:ahocorasick` | Apache 2.0 |
| Aho-Corasick double-array trie | `com.hankcs:aho-corasick-double-array-trie` | Apache 2.0 |
| Apache Commons CSV | `org.apache.commons:commons-csv` | Apache 2.0 |
| JMH | `org.openjdk.jmh:jmh-core` | GPLv2 with Classpath Exception |
| JOL | `org.openjdk.jol:jol-core` | GPLv2 with Classpath Exception |

`java.util.regex` is part of the JDK.

## Loghub datasets

The benchmarks use log datasets from Loghub (https://github.com/logpai/loghub) and Loghub-2.0
(https://github.com/logpai/loghub-2.0) as input. They are not stored in this repository: the Gradle build downloads
them from Zenodo into `src/jmh/resources/loghub/` when they are missing, and git ignores them there. They are not sanitized or
anonymized by their authors, and are not covered by this repository's Apache 2.0 license. The Loghub license notice,
which has to be included in all copies of the datasets, follows.

> **LICENSE OF LOGHUB-2.0**
>
> The datasets are freely available for research or academic work, subject to the following condition: For any usage
> or distribution of the loghub-2.0 datasets, please refer to the loghub repository URL
> (https://github.com/logpai/loghub-2.0) and cite the following loghub papers where applicable.
>
> + Zhihan Jiang, Jinyang Liu, Junjie Huang, Yichen Li, Yintong Huo, Jiazhen Gu, Zhuangbin Chen, Jieming Zhu,
>   Michael R. Lyu. A Large-scale Evaluation for Log Parsing Techniques: How Far are We? In ISSTA, 2024.
> + Jieming Zhu, Shilin He, Pinjia He, Jinyang Liu, Michael R. Lyu. Loghub: A Large Collection of System Log
>   Datasets for AI-driven Log Analytics. In ISSRE, 2023.
>
> The above license notice shall be included in all copies of the datasets.

> **LICENSE OF LOGHUB**
>
> The datasets are freely available for research or academic work, subject to the following condition: For any usage
> or distribution of the loghub datasets, please refer to the loghub repository URL
> (https://github.com/logpai/loghub) and cite the following loghub paper where applicable.
>
> Jieming Zhu, Shilin He, Pinjia He, Jinyang Liu, Michael R. Lyu. Loghub: A Large Collection of System Log Datasets
> for AI-driven Log Analytics. In ISSRE, 2023.
>
> The above license notice shall be included in all copies of the datasets.
