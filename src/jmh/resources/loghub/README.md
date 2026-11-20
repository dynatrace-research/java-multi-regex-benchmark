# Loghub datasets

The benchmarks read the Loghub-2.0 log datasets from this directory (`<name>/<name>_full.log` and
`<name>/<name>_full.log_templates.csv`). The data is not part of this repository: the Gradle build downloads it
automatically when it is missing (`scripts/download_loghub.py`). It is fetched from the Loghub-2.0 Zenodo record
(https://doi.org/10.5281/zenodo.8275861), checked against fixed checksums, and ignored by git.

Loghub (https://github.com/logpai/loghub) and Loghub-2.0 (https://github.com/logpai/loghub-2.0) are by the LogPAI
team. The datasets are not covered by this repository's Apache 2.0 license, and the logs are not sanitized or
anonymized. If you use them, please cite:

- Zhihan Jiang, Jinyang Liu, Junjie Huang, Yichen Li, Yintong Huo, Jiazhen Gu, Zhuangbin Chen, Jieming Zhu,
  Michael R. Lyu. A Large-scale Evaluation for Log Parsing Techniques: How Far are We? In ISSTA, 2024.
- Jieming Zhu, Shilin He, Pinjia He, Jinyang Liu, Michael R. Lyu. Loghub: A Large Collection of System Log Datasets
  for AI-driven Log Analytics. In ISSRE, 2023.

The full license notices are in `THIRD_PARTY_NOTICES.md`.
