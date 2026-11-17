#!/usr/bin/env python3
"""The scope inventories, per pull request (#2018).

DoorInventoryTest and ScopeBoundaryInventoryTest (hub, rag) are Java tests, and no Java test runs on a pull
request: a door that forgot to decide about a scope-bound caller merged green and was caught by the next nightly, days
later. This script runs the same sweeps over the same sources, with the same lists — read from
scripts/scope-inventory.json, the file the Java tests read too — and the same text rule (SourceTree.code: comments
and imports are not code).

    python3 scripts/check-scope-inventory.py            # sweep the repository; exit 1 on a door that decides nothing
    python3 scripts/check-scope-inventory.py --selftest # the rules' own cases, as the Java tests pin them
    python3 scripts/check-scope-inventory.py --root DIR # sweep another tree (a mutated copy, to see it red)

The deciding calls of ScopeGrants and ScopeAccess are read off their @ScopeDecision methods, as
ScopeBoundaryRule.decidingCalls reads them by reflection. A module the open edition prunes (lumnik-rag) is skipped and
said so. Not repeated here: the Java tests' reflection checks (ApplyScopeBoundary is void, readableTable hands back a
table, @TenantWide is bound to its filter) — those stay nightly.
"""
import json
import re
import sys
from pathlib import Path

DATA = "scripts/scope-inventory.json"


def code(java_source: str) -> str:
    """SourceTree.code: line comments first (a '/*' inside one must not open a block), then blocks, then imports."""
    s = re.sub(r"(?m)//[^\n]*", " ", java_source)
    s = re.sub(r"(?s)/\*.*?\*/", " ", s)
    return re.sub(r"(?m)^\s*import\s+[^;]+;", " ", s)


DECISION = re.compile(r"@ScopeDecision\s+public\s+static\s+[\w<>,?\s\[\]]+?\s(\w+)\s*\(")


def deciding_calls(decider_name: str, decider_code: str) -> list:
    """ScopeBoundaryRule.decidingCalls: 'Name.method(' for each public static @ScopeDecision method."""
    return sorted({f"{decider_name}.{m}(" for m in DECISION.findall(decider_code)})


def names_any(tokens, stripped: str) -> bool:
    """ScopeBoundaryRule.touches and .consults: one of the tokens, in code already through code()."""
    return any(t in stripped for t in tokens)


def path_of(src: str) -> str:
    at = src.find('@Path("')
    if at < 0:
        return "(no @Path)"
    end = src.find('"', at + 7)
    return "(no @Path)" if end < 0 else src[at + 7:end]


def sources(root: Path, edit) -> dict:
    """Every .java file under root, read and stripped once: path -> (source, code)."""
    out = {}
    for p in sorted(root.rglob("*.java")):
        src = edit(p.read_text(encoding="utf-8"))
        out[p] = (src, code(src))
    return out


STRING_LITERAL = re.compile(r'"(?:\\.|[^"\\\n])*"')


PATH_ANNOTATION = re.compile(r"@(?:jakarta\.ws\.rs\.)?Path\s*\(")


def is_door(stripped: str) -> bool:
    """DoorInventoryTest.isDoor: any class carrying a @Path (@Path (, @jakarta.ws.rs.Path() whatever its URL (#2017) —
    outside a string literal."""
    return PATH_ANNOTATION.search(STRING_LITERAL.sub('""', stripped)) is not None


def check(root: Path, edit=lambda src: src) -> list:
    """Every problem of the four sweeps; edit rewrites each source as read (the selftest's mutations)."""
    data = json.loads((root / DATA).read_text(encoding="utf-8"))
    problems = []
    calls = {}
    for name, rel in data["deciders"].items():
        stripped = code(edit((root / rel).read_text(encoding="utf-8")))
        calls[name] = deciding_calls(name, stripped)
        marked, read = stripped.count("@ScopeDecision"), len(DECISION.findall(stripped))
        if not marked or marked != read:
            problems.append(f"{name}: {marked} @ScopeDecision mark(s), {read} method(s) read — the script would not "
                            "count as a decision what ScopeBoundaryRule.decidingCalls counts")

    modules = {m: sources(m, edit) for m in sorted(root.glob("lumnik-*/src/main/java"))}

    def tree(rel: str) -> dict:
        under = root / rel
        return {p: sc for files in modules.values() for p, sc in files.items() if p.is_relative_to(under)}

    def source_root(spec) -> str:
        return f"{spec['module']}/src/main/java/{spec['package']}"

    def boundary(key: str, files: dict, helpers: list) -> None:
        """One scope-touching sweep. The hub's counts doors.helper_deciders, as ScopeBoundaryInventoryTest (hub) does;
        the rag one does not, as lumnik-rag's does not."""
        spec = data[key]
        if not (root / spec["module"]).is_dir():
            print(f"skip {key}: {spec['module']} is not in this tree (pruned from the open edition)")
            return
        if not (root / source_root(spec)).is_dir():
            problems.append(f"{key}: {source_root(spec)} does not exist — the sweep would police nothing")
            return
        deciding = calls[spec["decider"]] + spec["extra_deciding_calls"] + helpers
        doors = {p: (src, c) for p, (src, c) in files.items()
                 if p.name.endswith(spec["suffix"]) and names_any(spec["touching"], c)}
        if not doors:
            problems.append(f"{key}: the sweep found no door touching a scope — an empty sweep is not a clean one")
        for p, (src, c) in doors.items():
            if not names_any(deciding, c) and p.name not in spec["exempt"]:
                problems.append(f"{key}: {p.name} ({path_of(src)}) works inside a scope and never asks whether the "
                                f"caller holds it — call {spec['decider']} (403 \"scope not granted\"), or exempt it "
                                f"in {DATA} with a reason")
        for name, reason in spec["exempt"].items():
            named = [c for p, (_, c) in files.items() if p.name == name]
            if not named:
                problems.append(f"{key}: exemption {name} — no such resource any more")
            elif not any(names_any(spec["touching"], c) for c in named):
                problems.append(f"{key}: exemption {name} — no longer touches a scope, so it says nothing")
            if not reason.strip():
                problems.append(f"{key}: exemption {name} — exempted without a reason")

    hub = tree(source_root(data["hub_scope_boundary"]))
    boundary("hub_scope_boundary", hub, list(data["doors"]["helper_deciders"]))
    boundary("rag_scope_boundary", tree(source_root(data["rag_scope_boundary"])), [])

    # The apply doors ask about the scope they are about to overwrite (#509), and every door taking a manifest is
    # named like one, so the file-name axis cannot narrow in silence.
    spec = data["apply_doors"]
    resource_suffix = data["hub_scope_boundary"]["suffix"]
    apply_doors = [(p, c) for p, (_, c) in hub.items() if p.name.endswith(spec["suffix"])]
    if not apply_doors:
        problems.append("apply doors: none found — an empty sweep is not a clean one")
    for p, c in apply_doors:
        if spec["stored_scope_call"] not in c:
            problems.append(f"apply doors: {p.name} applies a manifest but never asks ApplyScopeBoundary about the "
                            "STORED scope it is about to overwrite")
    takes_a_manifest = [p for p, (_, c) in hub.items()
                        if p.name.endswith(resource_suffix) and spec["manifest_marker"] in c]
    if not takes_a_manifest:
        problems.append(f"apply doors: no resource takes a manifest ({spec['manifest_marker']!r}) — the marker no longer "
                        "names the apply doors, and the name check below judges nothing")
    for p in takes_a_manifest:
        if not p.name.endswith(spec["suffix"]):
            problems.append(f"apply doors: {p.name} takes a manifest but is not named *{spec['suffix']}, "
                            "so the stored-scope sweep never sees it")

    # Every door — any class carrying a @Path, in every module, whatever its URL — decides about a scope-bound caller
    # (#1992, #2017): itself, or through a named helper that asks a decider.
    spec = data["doors"]
    # A helper's file must make a direct call, a @ScopeDecision method of ScopeGrants/ScopeAccess: a helper never
    # vouches for another, and @TenantWide decides only on a door, where its filter binds.
    direct = calls["ScopeGrants"] + calls["ScopeAccess"]
    extra = data["hub_scope_boundary"]["extra_deciding_calls"]
    deciding = direct + extra + list(spec["helper_deciders"])
    for call in extra:
        if not call.startswith("@"):
            problems.append(f"hub_scope_boundary: extra deciding call {call} is not an annotation — a call that decides "
                            "for its callers belongs in doors.helper_deciders, where its file is checked")
    doors = {".".join(p.relative_to(m).with_suffix("").parts): c
             for m, files in modules.items() for p, (_, c) in files.items() if is_door(c)}
    for call, rel in spec["helper_deciders"].items():
        helper = root / rel
        owner = re.split(r"\.|::", call)[0]
        if not helper.is_file():
            problems.append(f"doors: helper {call} — {rel} does not exist")
        elif helper.name != owner + ".java":
            problems.append(f"doors: helper {call} — {rel} is not {owner}'s file")
        elif not names_any(direct, code(edit(helper.read_text(encoding="utf-8")))):
            problems.append(f"doors: helper {call} — {rel} asks no decider, so the doors calling it decide nothing")

    def pruned(cls):
        """DoorInventoryTest.pruned: the open edition (no lumnik-rag) and a package in no module of it (io.lumnik.rag,
        io.lumnik.hub.pro). In the monorepo nothing is pruned, so a missing door is reported."""
        if (root / data["rag_scope_boundary"]["module"]).is_dir():
            return False
        pkg = cls.rsplit(".", 1)[0].replace(".", "/")
        return not any((m / pkg).is_dir() for m in modules)

    if type(spec["min_doors"]) is not int or spec["min_doors"] < 1:
        problems.append(f"doors: min_doors must be a positive integer, not {spec['min_doors']!r}")
    elif len(doors) < spec["min_doors"]:
        problems.append(f"doors: {len(doors)} found, fewer than {spec['min_doors']} — the sweep must find the doors it "
                        "polices")
    for name, c in doors.items():
        if not names_any(deciding, c) and name not in spec["exempt"]:
            problems.append(f"doors: {name} passes a caller bound to a scope:* role on its role alone — mark the class "
                            "@TenantWide when its subject is the whole tenant, ask ScopeGrants/ConnectorScope when it "
                            f"is one scope's, or exempt it in {DATA} with what it answers")
    for name, reason in spec["exempt"].items():
        if "." not in name:
            problems.append(f"doors: exemption {name} — not a class name (io.lumnik.….SomeResource)")
        elif name not in doors:
            if not pruned(name):
                problems.append(f"doors: exemption {name} — no such door")
        elif names_any(deciding, doors[name]):
            problems.append(f"doors: exemption {name} — decides now, so the exemption says nothing")
        if not reason.strip():
            problems.append(f"doors: exemption {name} — exempted without a reason")
    return problems


def selftest(root: Path) -> list:
    """The rules' cases, as DoorInventoryTest and ScopeBoundaryInventoryTest pin them; then check() itself, on
    this tree with its decisions taken out in memory, must see what it would then miss."""
    calls = ["ScopeGrants.granted(", "ScopeGrants.allows(", "@TenantWide"]

    def consults(src):
        return names_any(calls, code(src))

    cases = [
        (consults("class R { /* @TenantWide is not needed here */ }"), False, "a comment is not a decision"),
        (consults("import io.lumnik.common.security.TenantWide;\nclass R {}"), False, "an import is not one"),
        (consults("@TenantWide\nclass R {}"), True, "@TenantWide is a decision"),
        (consults("class R { Response no() { return error(ScopeGrants.REFUSAL); } }"), False,
         "naming the refusal constant is not deciding"),
        (consults("class R { void f() { if (!ScopeGrants.allows(s)) return; } }"), True, "allows() decides"),
        (consults("class R { // lm_integrator on /api/platform/*\n void f() { ScopeGrants.granted(); } }"), True,
         "a '/*' inside a line comment does not swallow the code after it"),
        (names_any(["SchemaCardStore"], code("class R { /* unlike SchemaCardStore */ }")), False,
         "a mention puts no door on the axis"),
        (names_any(["SchemaCardStore"], code("class R { @Inject SchemaCardStore cards; }")), True, "a use does"),
        (deciding_calls("ScopeGrants", "class G {\n @ScopeDecision\n public static Optional<Set<String>> granted() {}\n"
                        " public static String nameProblem(String s) {}\n @ScopeDecision\n public static boolean allowsAll"
                        "(Collection<String> s) {}\n}"),
         ["ScopeGrants.allowsAll(", "ScopeGrants.granted("], "only the @ScopeDecision methods are deciding calls"),
    ]
    cases += [
        (is_door(code('@Path("/api/platform/x") class R {}')), True, "a @Path makes a door"),
        (is_door(code('@Path("/api/entities/{name}/resolve") class R {}')), True, "whatever its URL (#2017)"),
        (is_door(code('@Path(BASE) class R { static final String BASE = "/x"; }')), True, "or a constant"),
        (is_door(code('class R { String u = "/api/platform/x"; }')), False, "a class with no @Path is no door"),
        (is_door(code('class R { /* @Path("/x") */ }')), False, "a @Path in a comment makes no door"),
        (is_door(code('class R { int at = src.indexOf("@Path(\\""); }')), False, "nor one in a string literal"),
        (is_door(code('@jakarta.ws.rs.Path("/x") class R {}')), True, "the qualified annotation makes a door"),
        (is_door(code('@Path ("/x") class R {}')), True, "so does a space before the parenthesis"),
    ]
    failed = [f"selftest: {why}" for got, want, why in cases if got != want]
    # Every decision taken out, deciders and their callers alike: each sweep must report what it would then miss.
    undecided = re.compile(r"ScopeGrants\.|ScopeAccess\.|ConnectorScope(\.|::)|DynamicTableScope\.readableTable|"
                           r"@TenantWide|ApplyScopeBoundary\.refuseUnless|@ScopeDecision")
    blind = check(root, lambda src: undecided.sub("Undecided_", src))
    data = json.loads((root / DATA).read_text(encoding="utf-8"))
    sweeps = ["doors:", "apply doors:", "ScopeGrants:"] + [
        f"{k}:" for k in ("hub_scope_boundary", "rag_scope_boundary") if (root / data[k]["module"]).is_dir()]
    for sweep in sweeps:
        if not any(p.startswith(sweep) for p in blind):
            failed.append(f"selftest: with every scope decision taken out, check() saw nothing in {sweep}")
    reports = [("passes a caller bound", "the door sweep")]
    if data["doors"]["helper_deciders"]:
        reports.append(("asks no decider", "the helper check"))
    for report, why in reports:
        if not any(p.startswith("doors:") and report in p for p in blind):
            failed.append(f"selftest: with every scope decision taken out, {why} saw nothing")
    return failed


def main(argv) -> int:
    root = Path(argv[argv.index("--root") + 1]) if "--root" in argv else Path(__file__).resolve().parent.parent
    if "--selftest" in argv:
        failed = selftest(root)
        for f in failed:
            print("FAIL " + f)
        print("selftest " + ("failed" if failed else "ok"))
        return 1 if failed else 0
    problems = check(root)
    for p in problems:
        print("FAIL " + p)
    print("scope inventory " + ("failed: %d problem(s)" % len(problems) if problems else "clean"))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
