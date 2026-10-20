#!/usr/bin/env python3
"""Flags fully-qualified type names that should be imported.

The rule is the mainstream Java one (Google Java Style §3.3.1, Checkstyle, IntelliJ):
**import the type; write it long only where Java leaves no choice.** One category stays
qualified, and this script must never report it — a guard that cries about a decision the
language forces teaches people to ignore it:

  · A genuine clash — the file already binds that simple name to a DIFFERENT type. Java has
    no import aliasing, so one of the two must be written long: io.lumnik.llm.spi.ChatMessage in
    LangChainChatPort, where dev.langchain4j's ChatMessage is imported, and java.sql.Date in
    ProcessRuleEvaluator and its test, where java.util.Date is. The run counts them.

This replaced a wider set of exceptions on 2026-08-27. PR #181 had also kept single-use types
long ("an import costs a line to save a few characters") and claimed the Agroal @DataSource
qualifier stayed long everywhere. Neither survived contact with the code: the Agroal rule was
never followed (25 files already imported it, 9 in main), and the single-use rule left 402
fully-qualified names in the tree, read at every pass to save an import block an IDE folds.

Everything else is reported:

  A. The exact type is already available short in this file — imported explicitly, or covered
     by a wildcard import — and is still written long. Pure redundancy.
  B. The type lives in this file's OWN package, so its simple name is already visible. Shorten
     it; adding an import would be the same-package smell PR #181 swept. A package spans source
     roots, so the sibling may sit under src/main while the file sits under src/test — checking
     the directory is not enough, and one such import slipped through this way (caught on #331).
  C. The type is not imported at all. Import it, and shorten the use.

Run from anywhere:  scripts/check-fqn-hygiene.py
Exit 0 = clean, 1 = violations found, 2 = the scan itself could not run.
"""

import re
import subprocess
import sys
from collections import Counter, defaultdict
from pathlib import Path

# Two or more lowercase package segments then a Capitalised type. The lookbehind keeps us off
# the tail of a longer expression (`foo.bar.Baz` inside `a.foo.bar.Baz`) and out of strings.
FQN = re.compile(r'(?<![\w.$"])((?:[a-z][a-zA-Z0-9_]*\.){2,}[A-Z][A-Za-z0-9_]*)')
IMPORT = re.compile(r"(?m)^\s*import\s+(?:static\s+)?([\w.*]+);")
PACKAGE = re.compile(r"(?m)^\s*package\s+([\w.]+);")


def code_only(src: str) -> str:
    """Blank every non-code region, one character at a time, preserving offsets.

    A regex cannot do this: quotes appear inside comments and comment markers appear inside
    strings, so whichever is stripped first corrupts the other. Java is scanned the way the
    compiler scans it — left to right, one state at a time.
    """
    out = list(src)
    n, i = len(src), 0
    NUL = "\x00"
    while i < n:
        c = src[i]
        if src.startswith('"""', i):                       # text block
            i += 3
            while i < n and not src.startswith('"""', i):
                if src[i] == "\\": out[i] = NUL; i += 1
                if i < n: out[i] = NUL; i += 1
            i += 3
        elif c == '"':                                     # string literal
            out[i] = NUL; i += 1
            while i < n and src[i] != '"':
                if src[i] == "\\": out[i] = NUL; i += 1
                if i < n: out[i] = NUL; i += 1
            if i < n: out[i] = NUL; i += 1
        elif c == "'":                                     # char literal
            out[i] = NUL; i += 1
            while i < n and src[i] != "'":
                if src[i] == "\\": out[i] = NUL; i += 1
                if i < n: out[i] = NUL; i += 1
            if i < n: out[i] = NUL; i += 1
        elif src.startswith("//", i):                      # line comment
            while i < n and src[i] != "\n": out[i] = NUL; i += 1
        elif src.startswith("/*", i):                      # block comment
            while i < n and not src.startswith("*/", i): out[i] = NUL; i += 1
            for _ in range(2):
                if i < n: out[i] = NUL; i += 1
        else:
            i += 1
    # import/package lines are declarations, not uses
    text = "".join(out)
    lines = text.split("\n")
    for k, l in enumerate(lines):
        if l.lstrip().startswith(("import ", "package ")): lines[k] = NUL * len(l)
    return "\n".join(lines)


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    try:
        listing = subprocess.run(["git", "ls-files", "*.java"], cwd=root,
                                 capture_output=True, text=True, check=True).stdout.split()
    except (subprocess.CalledProcessError, OSError) as e:
        print(f"❌ cannot list Java sources: {e}", file=sys.stderr)
        return 2
    if not listing:
        print("❌ git ls-files '*.java' matched nothing — refusing to report a clean tree",
              file=sys.stderr)
        return 2

    redundant, same_package, unimported = (defaultdict(list) for _ in range(3))
    allowed = Counter()

    # Every type the repo declares, as (package, simple name). Built from the package line
    # rather than the directory, because src/main and src/test share packages.
    declared_in = set()
    for rel in listing:
        head = (root / rel).read_text(encoding="utf-8", errors="ignore")[:4000]
        m = PACKAGE.search(head)
        if m:
            declared_in.add((m.group(1), Path(rel).stem))

    for rel in listing:
        # Explicit UTF-8: Path.read_text() otherwise follows the platform locale (cp1252 on a
        # Windows shell), and Maven compiles this source as UTF-8 regardless of who runs it.
        try:
            src = (root / rel).read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError) as e:
            print(f"❌ cannot read {rel}: {e}", file=sys.stderr)
            print("   refusing to report on a tree this scan could not read in full",
                  file=sys.stderr)
            return 2
        imports = set(IMPORT.findall(src))
        by_simple = {i.rsplit(".", 1)[1]: i for i in imports if not i.endswith(".*")}
        wildcards = {i[:-2] for i in imports if i.endswith(".*")}
        own = PACKAGE.search(src)
        own_package = own.group(1) if own else None

        for fqn, count in Counter(FQN.findall(code_only(src))).items():
            package, simple = fqn.rsplit(".", 1)
            if fqn in imports or package in wildcards:
                redundant[rel].append((fqn, count))          # A
            elif package == own_package and (package, simple) in declared_in:
                same_package[rel].append((fqn, count))       # B
            elif simple in by_simple:
                allowed["clash Java forces — the simple name is bound to another type"] += count
            else:
                unimported[rel].append((fqn, count))         # B

    print("=== check-fqn-hygiene ===")
    for reason, n in sorted(allowed.items()):
        print(f"  · {n:4d} left qualified on purpose — {reason}")

    total = 0
    for label, found in (("already available short in this file", redundant),
                         ("in this file's own package — shorten, do NOT import", same_package),
                         ("written long instead of imported", unimported)):
        n = sum(c for v in found.values() for _, c in v)
        total += n
        if n:
            print(f"\n❌ {n} occurrence(s) {label}:")
            for rel in sorted(found):
                print(f"    {rel}")
                for fqn, c in sorted(found[rel]):
                    print(f"        {c}× {fqn}")

    if total:
        print(f"\nFQN hygiene check FAILED ({total} occurrence(s) to shorten).")
        return 1
    print("\nFQN hygiene check PASSED.")
    return 0


def selftest() -> int:
    """Three cases the scanner got wrong before, kept executable.

    The second one is why this exists: a regex-based scanner stripped strings before comments,
    so an unbalanced quote in a javadoc ({@code password="x" rogueOption="y}) paired with the
    opening quote of the next real string and exposed its contents as code. The sweep then
    shortened a fully-qualified class name INSIDE a JAAS config string. One such corruption was
    caught by a test; a second, in a {@link}, was caught by nothing.
    """
    cases = [
        ("a plain use is code",
         "  io.lumnik.Foo.bar();\n", ["io.lumnik.Foo"]),
        ("an unbalanced quote in a comment must not expose the next string",
         '  /** e.g. {@code password="x" rogue="y} */\n'
         '  String s = "org.apache.kafka.common.security.plain.PlainLoginModule required ";\n', []),
        ("a comment marker inside a string is not a comment",
         '  String url = "http://example.com/a.B";\n  io.lumnik.Foo.bar();\n', ["io.lumnik.Foo"]),
    ]
    bad = 0
    for name, src, expected in cases:
        got = FQN.findall(code_only(src))
        ok = got == expected
        print(f"  {'ok  ' if ok else 'FAIL'} {name}")
        if not ok:
            print(f"       expected {expected}, got {got}")
            bad += 1
    print("selftest PASSED" if not bad else f"selftest FAILED ({bad})")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(selftest() if "--selftest" in sys.argv else main())
