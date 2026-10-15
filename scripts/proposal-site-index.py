#!/usr/bin/env python3
"""Maintains catalogue-index.json at the lumnik-site root: which catalogue versions are served, and
the digest of each (spec 2026-10-04, section 6).

A static host cannot list catalogue/, so the questionnaire reads this pointer, then fetches exactly
the version it wants and checks it against the digest recorded here. The digest is the catalogue's
own (scripts/check-proposal-catalogue.py, digest()) — imported, never re-implemented: the canonical
form has one implementation on this side, and the site's JavaScript is proven equal to it by a test.

The index lives OUTSIDE catalogue/ on purpose: publish-proposal-catalogue.sh treats every
catalogue/*.json as a reference catalogue for the immutability check, and a pointer is not one.

Run:  scripts/proposal-site-index.py --site DIR --catalogue FILE   # record FILE's version in DIR/catalogue-index.json
      scripts/proposal-site-index.py --selftest
Exit 0 = written/clean, 1 = refused, 2 = could not run.
"""

import argparse
import importlib.util
import json
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("check_proposal_catalogue", HERE / "check-proposal-catalogue.py")
_catalogue_tool = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_catalogue_tool)

INDEX = "catalogue-index.json"


class Refusal(Exception):
    pass


def version_key(version: str):
    """Dot-separated numbers compared numerically: 2026.10.0 < 2026.10.1 < 2026.11.0."""
    try:
        return tuple(int(part) for part in version.split("."))
    except ValueError:
        raise Refusal(f"catalogue version {version!r} is not dot-separated numbers") from None


def updated(index: dict, version: str, digest: str) -> dict:
    """A published version's digest never changes; a new version is an addition."""
    versions = dict(index.get("versions", {}))
    if version in versions and versions[version] != digest:
        raise Refusal(f"{version} is indexed as {versions[version]} and would become {digest} — a published version is immutable")
    versions[version] = digest
    ordered = sorted(versions, key=version_key)
    return {"latest": ordered[-1], "versions": {v: versions[v] for v in ordered}}


def read_catalogue(file: Path) -> dict:
    """Valid JSON is not enough: a list or a bare string is 'could not run', not a traceback."""
    catalogue = _catalogue_tool.load_json(file.read_text(encoding="utf-8"))
    if not isinstance(catalogue, dict) or not isinstance(catalogue.get("catalogue"), str):
        raise ValueError(f"{file} is not a catalogue: an object with a string 'catalogue' version is required")
    return catalogue


def read_index(file: Path) -> dict:
    if not file.exists():
        return {}
    index = _catalogue_tool.load_json(file.read_text(encoding="utf-8"))
    versions = index.get("versions", {}) if isinstance(index, dict) else None
    if not isinstance(versions, dict) or not all(isinstance(d, str) for d in versions.values()):
        raise ValueError(f"{file} is not a catalogue index: an object whose 'versions' maps versions to digest strings is required")
    return index


def write(site: Path, catalogue_file: Path) -> dict:
    catalogue = read_catalogue(catalogue_file)
    file = site / INDEX
    index = read_index(file)
    result = updated(index, catalogue["catalogue"], _catalogue_tool.digest(catalogue))
    file.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return result


def selftest() -> int:
    d1, d2, d3 = "sha256:1", "sha256:2", "sha256:3"
    bad = 0

    def expect(name, ok):
        nonlocal bad
        print(f"  {'ok  ' if ok else 'FAIL'} {name}")
        bad += 0 if ok else 1

    first = updated({}, "2026.10.0", d1)
    expect("a first version becomes latest", first == {"latest": "2026.10.0", "versions": {"2026.10.0": d1}})
    both = updated(first, "2026.9.0", d2)
    expect("latest is numeric, not lexicographic (2026.9.0 < 2026.10.0)", both["latest"] == "2026.10.0")
    expect("versions are kept in numeric order", list(both["versions"]) == ["2026.9.0", "2026.10.0"])
    expect("re-indexing the same digest is a no-op", updated(first, "2026.10.0", d1) == first)
    try:
        updated(first, "2026.10.0", d3)
        expect("a changed digest for a published version is refused", False)
    except Refusal:
        expect("a changed digest for a published version is refused", True)
    try:
        version_key("2026.10.x")
        expect("a non-numeric version is refused", False)
    except Refusal:
        expect("a non-numeric version is refused", True)
    with tempfile.TemporaryDirectory() as tmp:
        site, cat = Path(tmp), Path(tmp) / "c.json"
        cat.write_text('{"catalogue": "2026.10.0", "rules": []}')
        written = write(site, cat)
        expect("write records the catalogue's own digest",
               written["versions"]["2026.10.0"] == _catalogue_tool.digest({"catalogue": "2026.10.0", "rules": []}))
        expect("write is idempotent", write(site, cat) == written)

    def exit_code(index_text, catalogue_text):
        """The real CLI, as a process: 'could not run' is exit 2, never a traceback."""
        with tempfile.TemporaryDirectory() as tmp:
            site, cat = Path(tmp), Path(tmp) / "c.json"
            if index_text is not None:
                (site / INDEX).write_text(index_text)
            cat.write_text(catalogue_text)
            run = subprocess.run([sys.executable, str(Path(__file__).resolve()), "--site", str(site), "--catalogue", str(cat)],
                                 capture_output=True, text=True)
            return run.returncode, "Traceback" in run.stderr

    good = '{"catalogue": "2026.10.0", "rules": []}'
    for name, index_text, catalogue_text in [
        ("an index that is a JSON list", "[]", good),
        ("an index whose versions is not an object", '{"versions": 5}', good),
        ("an index whose digest is not a string", '{"versions": {"2026.10.0": 7}}', good),
        ("a catalogue that is a JSON list", None, "[]"),
        ("a catalogue without a version string", None, '{"rules": []}'),
    ]:
        code, traceback = exit_code(index_text, catalogue_text)
        expect(f"{name} is 'could not run' (2), not a traceback", code == 2 and not traceback)
    print("selftest PASSED" if not bad else f"selftest FAILED ({bad})")
    return 1 if bad else 0


def main() -> int:
    parser = argparse.ArgumentParser(description="catalogue-index.json maintenance")
    parser.add_argument("--site")
    parser.add_argument("--catalogue")
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args()
    if args.selftest:
        return selftest()
    if not (args.site and args.catalogue):
        parser.error("--site and --catalogue are both required")
    try:
        result = write(Path(args.site), Path(args.catalogue))
    except Refusal as e:
        print(f"refused: {e}", file=sys.stderr)
        return 1
    except (OSError, ValueError, KeyError) as e:
        print(f"could not run: {type(e).__name__}: {e}", file=sys.stderr)
        return 2
    print(f"indexed: latest {result['latest']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
