#!/usr/bin/env python3
"""Download the Loghub-2.0 datasets that the benchmarks and tests read.

What it does: downloads one zip per dataset from the Loghub-2.0 Zenodo record (DOI 10.5281/zenodo.8275861), checks it
against the MD5 checksum below, and extracts `<name>_full.log` and `<name>_full.log_templates.csv` into
`src/jmh/resources/loghub/<name>/`, where `LoghubWorkload` and `RegexEngineTests` expect them. Datasets that are
already present are skipped unless `--force` is given.

How it is used: `./gradlew downloadLoghub`, or `python3 scripts/download_loghub.py [--force] [--out <dir>]`.

The datasets are subject to the Loghub license (see `src/jmh/resources/loghub/README.md`).
"""
import argparse
import hashlib
import io
import sys
import urllib.request
import zipfile
from pathlib import Path

ZENODO_URL = "https://zenodo.org/api/records/8275861/files/{name}.zip/content"
ROOT = Path(__file__).resolve().parent.parent
DEFAULT_OUT = ROOT / "src" / "jmh" / "resources" / "loghub"

# MD5 checksums of the zip files, as published by Zenodo. Must match the datasets listed in `LoghubWorkload`.
DATASETS = {
    "Apache": "0c52d166e5ee118b2d19d8a57cd2d0b4",
    "Hadoop": "e8d413f0ea3f3e90956f185da9d635a8",
    "HealthApp": "6b90126e1c78591454aef10ecc1da098",
    "HPC": "07d035b50ede61f7850e0453e43f85bb",
    "Linux": "a1a1db599519eb6b12b3368f37d274f7",
    "Mac": "e5b392e013be0f10a55b5b009f4b9c9d",
    "OpenSSH": "fef920579c366462ea7d341af8f533d8",
    "OpenStack": "707faeedf8b1b9f920c073114139a023",
    "Proxifier": "2e4b67dc68c3a4f1c18e267c341c52a2",
    "Zookeeper": "e696a94a9b703eb3fd93a2c78e3050ed",
}
SUFFIXES = ("_full.log", "_full.log_templates.csv")


def download(name, expected_md5):
    with urllib.request.urlopen(ZENODO_URL.format(name=name), timeout=120) as response:
        data = response.read()
    actual = hashlib.md5(data).hexdigest()
    if actual != expected_md5:
        raise RuntimeError(f"{name}.zip: checksum mismatch (expected {expected_md5}, got {actual})")
    return data


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT, help="target directory (default: %(default)s)")
    parser.add_argument("--force", action="store_true", help="download even if the files are already present")
    args = parser.parse_args()

    failed = []
    for name, md5 in DATASETS.items():
        target = args.out / name
        if not args.force and all((target / f"{name}{s}").is_file() for s in SUFFIXES):
            continue
        try:
            print(f"{name} data missing. Downloading...")
            with zipfile.ZipFile(io.BytesIO(download(name, md5))) as archive:
                target.mkdir(parents=True, exist_ok=True)
                for suffix in SUFFIXES:
                    member = f"{name}/{name}{suffix}"
                    (target / f"{name}{suffix}").write_bytes(archive.read(member))
        except Exception as error:  # report every dataset that fails, not just the first
            print(f"{name}: FAILED: {error}", file=sys.stderr)
            failed.append(name)
    if failed:
        sys.exit(f"failed: {', '.join(failed)}")


if __name__ == "__main__":
    main()
