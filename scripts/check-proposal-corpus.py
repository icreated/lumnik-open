#!/usr/bin/env python3
"""Keeps the proposal-dossier fixture corpus explicit, and its vendored copy honest.

The corpus (lumnik-hub/src/test/resources/proposal/fixtures/) is the executable contract that the
site's reader is judged against (spec 2026-10-04, section 7). The site holds a vendored COPY, a
publication artefact written only by scripts/publish-proposal-catalogue.sh. This script owns the
one definition of the corpus digest:

  inventory = the .md files under valid/, valid-recheck/ and invalid/ — nothing else. corpus.digest
              itself is excluded (a file cannot hold its own digest); any OTHER file is a problem,
              so the inventory cannot hide a stray one.
  digest    = sha256 over the lines "<relative path>\\t<sha256 of the file's bytes>", sorted by
              path, joined by "\\n" — it moves on an addition, a deletion, a rename and an edit.

Run:  scripts/check-proposal-corpus.py                  # corpus.digest must match the fixtures
      scripts/check-proposal-corpus.py --write          # refresh corpus.digest after a fixture change
      scripts/check-proposal-corpus.py --check-site DIR # drift between this corpus and a lumnik-site checkout
      scripts/check-proposal-corpus.py --publish-to DIR # replace the site's vendored copy (publication only)
      scripts/check-proposal-corpus.py --selftest
Exit 0 = clean, 1 = problems found, 2 = the check itself could not run.
"""

import argparse
import hashlib
import shutil
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FIXTURES = ROOT / "lumnik-hub/src/test/resources/proposal/fixtures"
DIGEST_FILE = "corpus.digest"
VERDICTS = ("valid", "valid-recheck", "invalid")
SITE_FIXTURES = "tests/fixtures/proposal"


def inventory(directory: Path):
    """(problems, relative paths of the inventory, sorted)."""
    problems, paths = [], []
    for path in sorted(p for p in directory.rglob("*") if p.is_file()):
        rel = path.relative_to(directory).as_posix()
        if rel == DIGEST_FILE:
            continue
        parts = rel.split("/")
        if len(parts) == 2 and parts[0] in VERDICTS and parts[1].endswith(".md"):
            paths.append(rel)
        else:
            problems.append(f"{rel} is outside the corpus inventory (only .md files under {', '.join(VERDICTS)}/)")
    if not paths:   # a corpus with no fixture replays nothing: refuse it rather than publish "nothing"
        problems.append(f"the corpus is empty: no .md fixture under {', '.join(VERDICTS)}/")
    return problems, sorted(paths)


def corpus_digest(directory: Path, paths) -> str:
    lines = [f"{rel}\t{hashlib.sha256((directory / rel).read_bytes()).hexdigest()}" for rel in sorted(paths)]
    return "sha256:" + hashlib.sha256("\n".join(lines).encode("utf-8")).hexdigest()


def recorded(directory: Path):
    file = directory / DIGEST_FILE
    return file.read_text(encoding="utf-8").strip() if file.exists() else None


def check(directory: Path, where: str) -> list:
    problems, paths = inventory(directory)
    if problems:
        return problems
    current, stated = corpus_digest(directory, paths), recorded(directory)
    if stated is None:
        return [f"{where}: no {DIGEST_FILE} — run --write"]
    if stated != current:
        return [f"{where}: the fixtures changed without {DIGEST_FILE} (recorded {stated}, content {current})"]
    return []


def publish(source: Path, site: Path) -> None:
    """Replaces the site's vendored copy wholesale, so a deleted fixture vanishes there too."""
    problems = check(source, "monorepo")
    if problems:
        raise ValueError("; ".join(problems))
    _, paths = inventory(source)
    target = site / SITE_FIXTURES
    if target.exists():
        shutil.rmtree(target)
    for rel in paths:
        (target / rel).parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source / rel, target / rel)
    shutil.copyfile(source / DIGEST_FILE, target / DIGEST_FILE)
    # Bytes as committed: no end-of-line conversion may alter a vendored fixture. Scoped to the corpus
    # (not "*"), and kept one level up so the corpus directory holds nothing but its inventory.
    (site / "tests/fixtures/.gitattributes").write_text("proposal/** -text\n", encoding="utf-8")


def drift(source: Path, site: Path) -> list:
    """The monorepo is held to its own digest FIRST: comparing only the two recorded strings reads as
    clean when a fixture moved without its digest, the site still matching the stale one."""
    problems = check(source, "monorepo")
    if problems:
        return problems
    problems = check(site / SITE_FIXTURES, "site") if (site / SITE_FIXTURES).exists() else ["site: no vendored corpus"]
    if not problems and recorded(site / SITE_FIXTURES) != recorded(source):
        problems.append(f"drift: the site holds {recorded(site / SITE_FIXTURES)}, the monorepo {recorded(source)} — publish again")
    return problems


def selftest() -> int:
    """The failures this guard exists for, kept executable."""
    def corpus(root: Path, files: dict):
        for rel, content in files.items():
            (root / rel).parent.mkdir(parents=True, exist_ok=True)
            (root / rel).write_bytes(content)
        _, paths = inventory(root)
        (root / DIGEST_FILE).write_text(corpus_digest(root, paths) + "\n", encoding="utf-8")

    base = {"valid/a.md": b"A", "invalid/b--x.md": b"B", "valid-recheck/c.md": b"C"}
    bad = 0
    results = []

    def expect(name, problems, expected):
        nonlocal bad
        ok = len(problems) == expected
        results.append(f"  {'ok  ' if ok else 'FAIL'} {name}" + ("" if ok else f"\n       expected {expected}, got {problems}"))
        bad += 0 if ok else 1

    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)

        def fresh(name, files=base):
            root = tmp / name
            corpus(root, files)
            return root

        expect("a consistent corpus passes", check(fresh("ok"), "t"), 0)
        r = fresh("edit"); (r / "valid/a.md").write_bytes(b"A2")
        expect("an edited fixture is caught", check(r, "t"), 1)
        r = fresh("add"); (r / "valid/new.md").write_bytes(b"N")
        expect("an added fixture is caught", check(r, "t"), 1)
        r = fresh("del"); (r / "valid/a.md").unlink()
        expect("a deleted fixture is caught", check(r, "t"), 1)
        r = fresh("ren"); (r / "valid/a.md").rename(r / "valid/z.md")
        expect("a renamed fixture is caught", check(r, "t"), 1)
        r = fresh("stray"); (r / "valid/readme.txt").write_bytes(b"x")
        expect("a stray file is refused, not ignored", check(r, "t"), 1)
        r = fresh("deep"); (r / "valid/sub").mkdir(); (r / "valid/sub/q.md").write_bytes(b"x")
        expect("a nested fixture is outside the inventory", check(r, "t"), 1)
        r = fresh("other"); (r / "misc").mkdir(); (r / "misc/q.md").write_bytes(b"x")
        expect("an .md under another directory is refused", check(r, "t"), 1)
        r = fresh("nodigest"); (r / DIGEST_FILE).unlink()
        expect("a missing corpus.digest is caught", check(r, "t"), 1)
        # an empty corpus replays nothing: refused at the source, never published as "nothing"
        r = tmp / "empty"; r.mkdir(); (r / DIGEST_FILE).write_text(corpus_digest(r, []) + "\n")
        expect("an empty corpus is not clean", check(r, "t"), 1)
        try:
            publish(r, tmp / "empty-site")
            outcome = "published"
        except ValueError:
            outcome = "refused"
        except Exception as e:  # a crash is not a refusal
            outcome = type(e).__name__
        expect("publishing an empty corpus is a refusal, not a crash", [] if outcome == "refused" else [outcome], 0)
        # the digest file is outside the inventory: rewriting it does not move the digest
        r = fresh("self"); _, p = inventory(r); before = corpus_digest(r, p)
        (r / DIGEST_FILE).write_text("sha256:0\n"); _, p = inventory(r)
        expect("corpus.digest does not feed its own digest", [] if corpus_digest(r, p) == before else ["moved"], 0)
        # publication: the site copy equals the source, and a hand edit there is caught
        src, site = fresh("src"), tmp / "site"
        site.mkdir(); publish(src, site)
        expect("a published copy verifies and matches", drift(src, site), 0)
        (site / SITE_FIXTURES / "valid/a.md").write_bytes(b"edited in the site")
        expect("a fixture edited in the site is caught", drift(src, site), 1)
        publish(src, site)
        (src / "valid/new.md").write_bytes(b"N"); _, p = inventory(src)
        (src / DIGEST_FILE).write_text(corpus_digest(src, p) + "\n")
        expect("the site behind the monorepo is reported as drift", drift(src, site), 1)
        publish(src, site)
        (src / "valid/new.md").unlink(); _, p = inventory(src)
        (src / DIGEST_FILE).write_text(corpus_digest(src, p) + "\n"); publish(src, site)
        expect("a fixture deleted in the monorepo vanishes from the site", [] if not (site / SITE_FIXTURES / "valid/new.md").exists() else ["still there"], 0)
        # drift() must also hold the monorepo to its own digest: the site can match the RECORDED digest
        # while the fixtures behind it have moved on, and two equal strings would read as clean.
        (src / "valid/a.md").write_bytes(b"A changed, digest not refreshed")
        expect("a monorepo fixture changed without its digest is drift, though the site matches the recorded one", drift(src, site), 1)
        (src / "valid/a.md").write_bytes(base["valid/a.md"])
        expect("the site copy marks ONLY the corpus -text", [] if (site / "tests/fixtures/.gitattributes").read_text() == "proposal/** -text\n" else ["no"], 0)
    print("\n".join(results))
    print("selftest PASSED" if not bad else f"selftest FAILED ({bad})")
    return 1 if bad else 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Proposal fixture corpus digest")
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--check-site", metavar="DIR")
    parser.add_argument("--publish-to", metavar="DIR")
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args()
    if args.selftest:
        return selftest()
    try:
        if args.write:
            problems, paths = inventory(FIXTURES)
            if problems:
                print("\n".join(f"  {p}" for p in problems))
                return 1
            (FIXTURES / DIGEST_FILE).write_text(corpus_digest(FIXTURES, paths) + "\n", encoding="utf-8")
            print(f"wrote {DIGEST_FILE}: {recorded(FIXTURES)}")
            return 0
        if args.publish_to:
            publish(FIXTURES, Path(args.publish_to))
            print(f"vendored the corpus into {args.publish_to}/{SITE_FIXTURES}")
            return 0
        problems = drift(FIXTURES, Path(args.check_site)) if args.check_site else check(FIXTURES, "monorepo")
    except (OSError, ValueError) as e:
        print(f"the corpus check could not run: {e}", file=sys.stderr)
        return 2
    for p in problems:
        print(f"  {p}")
    print("proposal corpus: clean" if not problems else f"proposal corpus: {len(problems)} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
