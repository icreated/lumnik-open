#!/usr/bin/env python3
"""The nightly's verdict on the Java tests, read from the reports rather than from Maven's exit code.

The nightly runs Maven with -Dmaven.test.failure.ignore=true --fail-at-end, so a failing test no longer
stops the reactor: every module still runs, and a red module no longer hides the ones behind it (#1075:
on 2026-10-15 one Oracle container that did not start skipped ~3 000 hub unit tests and ~420 hub ITs).
The price is that Maven's exit code stops meaning "the tests passed". This script is that verdict:

  1. failures   — every <failure>/<error> in the surefire and failsafe XML reports, by test;
  2. fork deaths — a failsafe-summary.xml carrying a failure message or a timeout;
  3. completeness — every test class the run selected has a report. A test JVM that dies, or a module
     Maven skipped, leaves classes with none; on 2026-10-15 the hub's fork started 384 of 421 IT
     classes and the summary still read "Tests run: 1633 ... Errors: 2".

Selected = the module is in the root pom's <modules>; a unit class matches surefire's default includes
(Test*, *Test, *Tests, *TestCase), an IT class matches *IT in a module whose pom declares failsafe, and
--it-test (the same value as -Dit.test) narrows the ITs. A class is a test class if it declares a test
annotation or extends another class (some ITs only inherit their tests); helpers such as TestNoopHook
are neither.

Run:  scripts/ci-test-report.py --unit                      # the `verify` job: unit + IT
      scripts/ci-test-report.py --it-test '*IT,!*S3*IT'      # the `verify_search` job: IT only
      scripts/ci-test-report.py --modules lumnik-hub --it-test 'SearchProviderContractIT'  # one module's step
      scripts/ci-test-report.py --selftest
Writes a Markdown summary to $GITHUB_STEP_SUMMARY when it is set.
Exit 0 = every selected test ran and passed, 1 = it did not, 2 = the check itself could not run.
"""

import argparse
import fnmatch
import os
import re
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
UNIT_PATTERNS = ["Test*", "*Test", "*Tests", "*TestCase"]
TEST_ANNOTATION = re.compile(r"@(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b")
EXTENDS = re.compile(r"\bclass\s+\w+(<[^{]*>)?\s+extends\s")


def modules(root: Path) -> list[str]:
    pom = (root / "pom.xml").read_text()
    block = re.search(r"<modules>(.*?)</modules>", pom, re.S)
    if not block:
        raise SystemExit("no <modules> in the root pom")
    return re.findall(r"<module>\s*([^<\s]+)\s*</module>", block.group(1))


def selected(name: str, patterns: list[str]) -> bool:
    include = [p for p in patterns if not p.startswith("!")]
    exclude = [p[1:] for p in patterns if p.startswith("!")]
    return any(fnmatch.fnmatchcase(name, p) for p in include) and not any(fnmatch.fnmatchcase(name, p) for p in exclude)


def expected(test_dir: Path, patterns: list[str]) -> set[str]:
    classes = set()
    for f in test_dir.rglob("*.java"):
        if selected(f.stem, patterns):
            source = f.read_text(errors="replace")
            if TEST_ANNOTATION.search(source) or EXTENDS.search(source):
                classes.add(".".join(f.relative_to(test_dir).with_suffix("").parts))
    return classes


def read_reports(report_dir: Path):
    """Returns (classes with a report, tests, skipped, failed tests as 'Class#method: message')."""
    classes, failed = set(), []
    tests = skipped = 0
    for f in sorted(report_dir.glob("TEST-*.xml")) if report_dir.is_dir() else []:
        suite = ET.parse(f).getroot()
        classes.add(suite.get("name") or f.name[5:-4])
        tests += int(suite.get("tests", 0))
        skipped += int(suite.get("skipped", 0))
        for case in suite.iter("testcase"):
            for kind in ("failure", "error"):
                for problem in case.findall(kind):
                    message = (problem.get("message") or problem.get("type") or "").strip().splitlines()
                    failed.append(f"{case.get('classname')}#{case.get('name')}: {kind}: {message[0][:200] if message else ''}")
    return classes, tests, skipped, failed


def fork_death(report_dir: Path) -> str | None:
    summary = report_dir / "failsafe-summary.xml"
    if not summary.is_file():
        return None
    root = ET.parse(summary).getroot()
    message = next((e.text for e in root.iter() if e.tag.endswith("failureMessage")), None)
    if message and message.strip():
        return message.strip().splitlines()[0][:300]
    if root.get("timeout") == "true":
        return "timeout"
    return None


def report(root: Path, unit: bool, it_patterns: list[str], only: list[str] | None = None):
    """Returns (problems, Markdown table rows); no problems means every selected test ran and passed."""
    problems, rows = [], []
    for module in modules(root):
        if only and module not in only:
            continue
        test_dir = root / module / "src" / "test" / "java"
        if not test_dir.is_dir():
            continue
        lanes = []
        if unit:
            lanes.append(("unit", "surefire-reports", UNIT_PATTERNS + ["!*IT"]))
        if "maven-failsafe-plugin" in (root / module / "pom.xml").read_text():
            lanes.append(("IT", "failsafe-reports", it_patterns))
        for lane, reports, patterns in lanes:
            want = expected(test_dir, patterns)
            if not want:
                continue
            report_dir = root / module / "target" / reports
            have, tests, skipped, failed = read_reports(report_dir)
            missing = sorted(want - have)
            death = fork_death(report_dir) if lane == "IT" else None
            problems += [f"{module} {lane}: FAILED {f}" for f in failed]
            if death:
                problems.append(f"{module} {lane}: the test JVM did not finish: {death}")
            if missing:
                shown = ", ".join(c.rsplit(".", 1)[-1] for c in missing[:15])
                more = f" and {len(missing) - 15} more" if len(missing) > 15 else ""
                problems.append(f"{module} {lane}: {len(missing)} of {len(want)} selected classes have no report "
                                f"(never ran): {shown}{more}")
            verdict = "✅" if not (failed or death or missing) else "❌"
            rows.append(f"| {module} | {lane} | {len(want & have)}/{len(want)} | {tests} | {skipped} | {len(failed)} | {verdict} |")
    return problems, rows


def main(argv) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--unit", action="store_true", help="also expect the surefire (unit) reports")
    parser.add_argument("--it-test", default="*IT", help="the -Dit.test value the run used (default *IT)")
    parser.add_argument("--modules", help="comma-separated: check only these modules (a step that built only them)")
    parser.add_argument("--title", default="Java tests", help="heading on the summary page (one per step that reports)")
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args(argv)
    if args.selftest:
        return selftest()
    try:
        only = [m.strip() for m in args.modules.split(",")] if args.modules else None
        problems, rows = report(args.root, args.unit, [p.strip() for p in args.it_test.split(",") if p.strip()], only)
    except (OSError, ET.ParseError, SystemExit) as e:
        print(f"ci-test-report: could not read the reports: {e}", file=sys.stderr)
        return 2
    table = ["| module | lane | classes reported | tests | skipped | failed | verdict |", "|---|---|---|---|---|---|---|", *rows]
    print("\n".join(table))
    for p in problems:
        print(p)
    print(f"\n{len(problems)} problem(s)" if problems else "\nEvery selected test class ran and passed.")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as out:
            out.write(f"### {args.title}\n\n" + "\n".join(table) + "\n\n")
            out.write("".join(f"- {p}\n" for p in problems) or "Every selected test class ran and passed.\n")
    return 1 if problems else 0


def selftest() -> int:
    """Builds a two-module tree and checks that each kind of hole turns the verdict red."""
    def suite(name, failure=None):
        case = f'<testcase classname="{name}" name="t">{failure or ""}</testcase>'
        return f'<testsuite name="{name}" tests="1" skipped="0" failures="0" errors="0">{case}</testsuite>'

    def tree(base: Path, *, drop=None, fail=None, death=None, it_test="*IT"):
        (base / "pom.xml").write_text("<project><modules><module>a</module><module>b</module></modules></project>")
        sources = {
            "a": {"p/OneTest": "@Test", "p/TestHelperHook": "", "p/InheritsIT": "class InheritsIT extends Base {}"},
            "b": {"q/TwoIT": "@Test", "q/SkippedByPatternS3IT": "@Test"},
        }
        for module, classes in sources.items():
            (base / module).mkdir()
            (base / module / "pom.xml").write_text("<artifactId>maven-failsafe-plugin</artifactId>")
            for path, body in classes.items():
                src = base / module / "src/test/java" / f"{path}.java"
                src.parent.mkdir(parents=True, exist_ok=True)
                src.write_text(body)
                fq = path.replace("/", ".")
                if fq == drop or not selected(fq.rsplit(".", 1)[-1], [p for p in it_test.split(",")] if fq.endswith("IT") else UNIT_PATTERNS) \
                        or fq.endswith("Hook"):
                    continue
                reports = base / module / "target" / ("failsafe-reports" if fq.endswith("IT") else "surefire-reports")
                reports.mkdir(parents=True, exist_ok=True)
                (reports / f"TEST-{fq}.xml").write_text(suite(fq, '<error message="boom"/>' if fq == fail else None))
            if death and module == "b":
                (base / "b/target/failsafe-reports/failsafe-summary.xml").write_text(
                    f'<failsafe-summary timeout="false"><failureMessage>{death}</failureMessage></failsafe-summary>')

    cases = [
        ("clean run", {}, 0),
        ("a unit class with no report", {"drop": "p.OneTest"}, 1),
        ("an IT that only inherits its tests, with no report", {"drop": "p.InheritsIT"}, 1),
        ("a failing test", {"fail": "q.TwoIT"}, 1),
        ("a test JVM that died", {"death": "There was an error in the forked process"}, 1),
    ]
    failures = 0
    for label, kwargs, want in cases:
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            tree(base, it_test="*IT,!*S3*IT", **kwargs)
            problems, _ = report(base, True, ["*IT", "!*S3*IT"])
            got = 1 if problems else 0
            ok = got == want
            failures += not ok
            print(f"{'ok  ' if ok else 'FAIL'} {label}: verdict {got}, expected {want}" + ("" if ok else f" {problems}"))
    with tempfile.TemporaryDirectory() as tmp:  # a module Maven skipped: its reports directory does not exist
        base = Path(tmp)
        tree(base, it_test="*IT,!*S3*IT")
        for f in (base / "b/target/failsafe-reports").iterdir():
            f.unlink()
        (base / "b/target/failsafe-reports").rmdir()
        problems, _ = report(base, True, ["*IT", "!*S3*IT"])
        ok = any("TwoIT" in p for p in problems) and not any("SkippedByPatternS3IT" in p for p in problems)
        failures += not ok
        print(f"{'ok  ' if ok else 'FAIL'} a skipped module is reported, a class the pattern excludes is not")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
