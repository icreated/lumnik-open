#!/usr/bin/env python3
"""Every Quarkus test application the suites build is listed here, with the reason it cannot be another one.

quarkus-junit builds one application per (test profile, test resources) group when it DISCOVERS the test
classes, before any test runs, and the test JVM keeps every one of them until the run ends: ~85 MB each,
measured in #1072. The hub's IT JVM died of it three nights out of six (10-09, 10-11, 10-15) after ten
profiles landed in two weeks — two of them byte for byte the same as an existing one. The fix (#1074) cut
seven applications and pinned -Xmx3g, which leaves room for about ten more; this keeps that room from
being spent without anyone deciding to.

So, over every module's src/test/java:

  1. a QuarkusTestProfile — implementing the interface, or extending a class that does — must be in
     PROFILES below with its reason. A new one is a new application for the whole run: reuse an existing
     profile when its overrides would do, and say why not when they would not;
  2. an entry of PROFILES whose class is gone fails too, so the list stays the inventory;
  3. a test resource declared on a test class makes applications the inventory cannot see:
     restrictToAnnotatedClass = true and TestResourceScope.RESTRICTED_TO_CLASS give ONE PER CLASS;
     @WithTestResource's default scope (MATCHING_RESOURCES, Quarkus >= 3.16) gives one per distinct set of
     resources. Declare the resource in a profile's testResources() instead: the classes of that profile
     then share one application, and the profile is listed. @WithTestResource(scope = GLOBAL) applies to
     every class and makes no application of its own: allowed.
  4. every reason in PROFILES is a non-empty sentence.

Comments are ignored, so a javadoc that explains the rule does not trip it.

Run:  scripts/check-test-profiles.py
      scripts/check-test-profiles.py --selftest
Exit 0 = clean, 1 = violations found, 2 = the check itself could not run.
"""

import argparse
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# module/Profile (nested: module/Outer.Inner) -> why it cannot be an existing profile of its module.
PROFILES = {
    # lumnik-hub — one failsafe JVM holds all of these (#1072)
    "lumnik-hub/RlsEnforcedProfile": "RLS really enforced: a non-owner app role against its own Postgres",
    "lumnik-hub/RlsOidcProfile": "RLS enforced AND real bearer JWTs on the same run",
    "lumnik-hub/BoundedSnapshotProfile": "RLS + a 120 s snapshot-read bound, so one export stall cannot cost the nightly half an hour (#755)",
    "lumnik-hub/SchedulerPausedProfile": "scheduler off: ITs drive the consumers by hand",
    "lumnik-hub/RunQueueEnabledProfile": "scheduler off + run queue on",
    "lumnik-hub/EndpointRunConcurrencyBoundIT.SmallAdminPool": "a concurrency bound small enough to reach; read at boot",
    "lumnik-hub/NoBypassOidcProfile": "dev bypass off, real signed JWTs (roles and scope grants)",
    "lumnik-hub/DevBypassOffProfile": "dev bypass off with NO OIDC provider: an unauthenticated request meets the real filters",
    "lumnik-hub/PlatformAuthIT.RealAuthProfile": "dev bypass off, groups as the role claim, no OIDC key: the platform door's own auth path",
    "lumnik-hub/AiDisabledProfile": "lumnik.ai.enabled=false",
    "lumnik-hub/AiDisabledAuthClosedIT.Profile": "AI off AND real auth: the AI doors close before the role gate",
    "lumnik-hub/RealAnalyticModelProfile": "the global @Analytic mock off, a real model answers (manual lane)",
    "lumnik-hub/HnswFilteredRecallClosedIT.HnswPlanProfile": "RLS + enable_sort=off on every connection, to force the HNSW plan",
    "lumnik-hub/ReadonlyTransactionsProfile": "readonly datasource enlisted in JTA, as in production (%test disables it)",
    "lumnik-hub/EntityResolutionPassPaginationIT.SmallBatch": "resolution batch size 2, to cross a page boundary with a few rows",
    "lumnik-hub/EntitySnapshotDownloadTimeoutIT.Profile": "snapshot timeout 2 s, to reach the timeout",
    "lumnik-hub/RateLimitFilterIT.Profile": "2 requests a minute on view-generate, to reach the limit",
    "lumnik-hub/SearchOutageApiContractProfile": "the outage search double pinned as provider",
    "lumnik-hub/SearchBackendOidcProfile": "real JWTs + the #674 search backend container",
    "lumnik-hub/SearchProviderContractRlsProfile": "real JWTs + RLS on its own Postgres + the #674 search backend container",
    # lumnik-search-pg
    "lumnik-search-pg/CorpusIsolationProfile": "its own corpus database, isolated from the module's other ITs",
    "lumnik-search-pg/SearchPgRlsEnforcedProfile": "RLS really enforced on the provider's schema",
    # lumnik-rag
    "lumnik-rag/AiDisabledProfile": "lumnik.ai.enabled=false",
    # lumnik-llm-langchain
    "lumnik-llm-langchain/AiSwitchOffSeamTest.Off": "lumnik.ai.enabled=false",
    "lumnik-llm-langchain/AiSwitchOnSeamTest.On": "lumnik.ai.enabled=true written explicitly: the same hub as an absent property",
    "lumnik-llm-langchain/AnalyticSingleAttemptTest.AgainstTheStub": "the analytic model aimed at a local stub server (#1067)",
}

CLASS = re.compile(r"\bclass\s+(\w+)(?:<[^>{]*>)?(?:\s+extends\s+([\w.]+)(?:<[^>{]*>)?)?(?:\s+implements\s+([^{]+))?\s*\{")
PER_CLASS = re.compile(r"restrictToAnnotatedClass\s*=\s*true|RESTRICTED_TO_CLASS")
WITH_TEST_RESOURCE = re.compile(r"@WithTestResource\b\s*(\([^)]*\))?")


def code(source: str) -> str:
    """The source without its comments. Read token by token, not by regex: a string such as "dir/*.java" is not
    the start of a comment (a regex would swallow the code up to the next "*/"), and "http://" is not one either."""
    out, i, n = [], 0, len(source)
    while i < n:
        if source.startswith('"""', i):                       # text block
            end = source.find('"""', i + 3)
            end = n if end < 0 else end + 3
            out.append(source[i:end]); i = end
        elif source[i] in "\"'":                               # string or char literal, escapes honoured
            quote, j = source[i], i + 1
            while j < n and source[j] != quote and source[j] != "\n":
                j += 2 if source[j] == "\\" else 1
            out.append(source[i:j + 1]); i = j + 1
        elif source.startswith("//", i):
            end = source.find("\n", i)
            i = n if end < 0 else end
        elif source.startswith("/*", i):
            end = source.find("*/", i + 2)
            i = n if end < 0 else end + 2
            out.append(" ")
        else:
            out.append(source[i]); i += 1
    return "".join(out)


def scan(root: Path):
    """Returns (profiles found as module/Key, [(file, why)] for test resources declared on a class)."""
    declared, restricted = {}, []
    for f in sorted(root.glob("*/src/test/java/**/*.java")):
        module, src = f.relative_to(root).parts[0], code(f.read_text(errors="replace"))
        if PER_CLASS.search(src):
            restricted.append((str(f.relative_to(root)), "a class-restricted test resource gives that class an application of its own"))
        elif any("GLOBAL" not in (args or "") for args in WITH_TEST_RESOURCE.findall(src)):
            restricted.append((str(f.relative_to(root)), "@WithTestResource (default scope MATCHING_RESOURCES) makes one application "
                                                         "per distinct set of resources, outside this inventory"))
        for name, extends, implements in CLASS.findall(src):
            key = f"{module}/{name if name == f.stem else f'{f.stem}.{name}'}"
            declared[key] = (module, extends.split(".")[-1], "QuarkusTestProfile" in implements)
    profiles = {k for k, (_, _, direct) in declared.items() if direct}
    grew = True
    while grew:  # a class extending a profile is a profile (RunQueueEnabledProfile extends SchedulerPausedProfile)
        bases = {(declared[p][0], p.rsplit("/", 1)[1].rsplit(".", 1)[-1]) for p in profiles}
        new = {k for k, (m, ext, _) in declared.items() if k not in profiles and ext and (m, ext) in bases}
        profiles |= new
        grew = bool(new)
    return profiles, restricted


def check(root: Path, inventory: dict) -> list[str]:
    profiles, restricted = scan(root)
    problems = [f"{p}: a test profile not in scripts/check-test-profiles.py — one more Quarkus application held for "
                f"the whole test run (~85 MB, #1072). Reuse an existing profile of its module if its overrides would do; "
                f"otherwise add it to PROFILES with the reason it cannot." for p in sorted(profiles - inventory.keys())]
    problems += [f"{p}: listed in PROFILES but no such profile exists any more — remove the entry."
                 for p in sorted(inventory.keys() - profiles)]
    problems += [f"{f}: {why} (#1072). Declare the resource in a profile's testResources() instead." for f, why in restricted]
    problems += [f"{p}: listed in PROFILES without a reason." for p, reason in sorted(inventory.items()) if not reason.strip()]
    return problems


def main(argv) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args(argv)
    if args.selftest:
        return selftest()
    try:
        problems = check(args.root, PROFILES)
    except OSError as e:
        print(f"check-test-profiles: could not read the tests: {e}", file=sys.stderr)
        return 2
    for p in problems:
        print(p)
    print(f"{len(problems)} problem(s)" if problems else f"{len(PROFILES)} test profiles, all listed with a reason.")
    return 1 if problems else 0


def selftest() -> int:
    """Each rule must bite on a tree built to break it, and stay quiet on one that does not."""
    base_files = {
        "m/src/test/java/p/BaseProfile.java": "class BaseProfile implements QuarkusTestProfile {}",
        "m/src/test/java/p/SomeIT.java": "/** says restrictToAnnotatedClass = true in prose */\nclass SomeIT {\n"
                                         "  public static class Inner extends BaseProfile {}\n}",
    }
    listed = {"m/BaseProfile": "r", "m/SomeIT.Inner": "r"}
    cases = [
        ("clean tree, javadoc mentioning the rule", {}, listed, False),
        ("an unlisted profile", {"m/src/test/java/p/NewProfile.java": "class NewProfile implements QuarkusTestProfile {}"}, listed, True),
        ("an unlisted subclass of a profile", {"m/src/test/java/p/More.java": "class More extends BaseProfile {}"}, listed, True),
        ("a stale entry", {}, {**listed, "m/GoneProfile": "r"}, True),
        ("a class-restricted resource", {"m/src/test/java/p/RIT.java": "@QuarkusTestResource(value = R.class, restrictToAnnotatedClass = true)\nclass RIT {}"}, listed, True),
        ("@WithTestResource, default scope", {"m/src/test/java/p/WIT.java": "@WithTestResource(R.class)\nclass WIT {}"}, listed, True),
        ("TestResourceScope.RESTRICTED_TO_CLASS", {"m/src/test/java/p/XIT.java": "@WithTestResource(value = R.class, scope = TestResourceScope.RESTRICTED_TO_CLASS)\nclass XIT {}"}, listed, True),
        ("@WithTestResource, GLOBAL scope", {"m/src/test/java/p/YIT.java": "@WithTestResource(value = R.class, scope = TestResourceScope.GLOBAL)\nclass YIT {}"}, listed, False),
        ("a blank reason", {}, {**listed, "m/BaseProfile": "  "}, True),
        ("an inline comment mentioning the rule", {"m/src/test/java/p/CIT.java": "class CIT {} // never restrictToAnnotatedClass = true"}, listed, False),
        ("a string holding \"/*\" does not hide the profile after it",
         {"m/src/test/java/p/G.java": 'class G { String g = "dir/*.java"; }\nclass Hidden implements QuarkusTestProfile {}\n'
                                      'class Z { String e = "*/"; String u = "http://x"; }'}, listed, True),
        ("same class name in another module is another profile", {"n/src/test/java/p/BaseProfile.java": "class BaseProfile {}"}, listed, False),
    ]
    failures = 0
    for label, extra, inventory, want_red in cases:
        with tempfile.TemporaryDirectory() as tmp:
            for path, body in {**base_files, **extra}.items():
                (Path(tmp) / path).parent.mkdir(parents=True, exist_ok=True)
                (Path(tmp) / path).write_text(body)
            red = bool(check(Path(tmp), inventory))
            ok = red == want_red
            failures += not ok
            print(f"{'ok  ' if ok else 'FAIL'} {label}: {'red' if red else 'green'}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
