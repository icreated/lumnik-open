#!/usr/bin/env python3
"""A compose file never hands the hub an empty value over a property that ships one (#1996).

In MicroProfile Config an empty value is not "unset": the highest source that holds the key wins, and an empty value
there removes the property. `LUMNIK_RATELIMIT_SCIM_RPM: ${LUMNIK_RATELIMIT_SCIM_RPM:-}` turned SCIM's shipped 600 rpm
into the default 60, and an empty `LUMNIK_RATELIMIT_TENANT_FACTOR` stopped the hub at startup (SRCFG00040) although
its @ConfigProperty has a defaultValue.

An empty VAR also beats the default of a `${VAR:7}` expression. The fix is to write the value out: `${NAME:-600}`.

    python3 scripts/check-compose-empty-env.py [--root DIR]
    python3 scripts/check-compose-empty-env.py --selftest
"""
import argparse
import pathlib
import re
import sys
import tempfile

# `NAME: ${VAR}`, `${VAR:-}`, `${VAR-}` or `""`, quoted or not, a comment after it; the list form `- NAME=` too
EMPTY_ENV = re.compile(
    r"^\s+(?:-\s+)?([A-Za-z_][A-Za-z0-9_]*)(?::\s*|=)"
    r"(?:([\"']?)\$\{[A-Za-z_][A-Za-z0-9_]*(?::?-)?\}\2|\"\"|''|(?<==))\s*(?:#.*)?$", re.M)
CONFIG_PROPERTY = re.compile(r"@ConfigProperty\s*\(((?:\"(?:[^\"\\]|\\.)*\"|[^)\"])*)\)", re.S)   # a `)` in a string
ATTRIBUTE = re.compile(r"\b(name|defaultValue)\s*=\s*\"((?:[^\"\\]|\\.)*)\"")
EMPTY_EXPRESSION = re.compile(r"^\$\{[^}:]+:\}$")   # `${LUMNIK_SMTP_USERNAME:}` ships nothing
REFERENCE = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_.-]*):([^}]+)\}")   # `${VAR:7}`, `${lumnik.smtp.host:localhost}`


def env_form(prop):
    """MicroProfile's environment name for a property: every non-alphanumeric becomes `_`, upper-cased."""
    return re.sub(r"[^A-Za-z0-9]", "_", prop).upper()


def shipped(root):
    """{environment form: (property, its shipped value, where)} for every property a deployment starts with."""
    base, prod = {}, {}
    for f in sorted(root.glob("*/src/main/resources/application.properties")):
        for n, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
            line = line.strip()
            if not line or line[0] in "#!" or "=" not in line:
                continue
            key, value = (s.strip() for s in line.split("=", 1))
            lines = base
            if key.startswith("%"):
                profiles, _, key = key[1:].partition(".")
                if "prod" not in profiles.split(","):
                    continue
                lines = prod
            lines[key] = (value, f"{f.relative_to(root)}:{n}")
    out = {}
    for key, (value, where) in {**base, **prod}.items():   # a %prod line overrides the base one
        if value and not EMPTY_EXPRESSION.match(value):
            out[env_form(key)] = (key, value, where)
        # `${VAR:7}`: an empty VAR beats the 7 too, the expression comes out empty
        for ref, default in REFERENCE.findall(value):
            out.setdefault(env_form(ref), (key, default, where))
    for f in sorted(root.glob("*/src/main/java/**/*.java")):
        text = f.read_text(encoding="utf-8")
        if "@ConfigProperty" not in text:
            continue
        for m in CONFIG_PROPERTY.finditer(text):
            attrs = dict(ATTRIBUTE.findall(m.group(1)))
            if "name" in attrs and attrs.get("defaultValue"):
                out.setdefault(env_form(attrs["name"]),
                               (attrs["name"], attrs["defaultValue"], str(f.relative_to(root))))
    return out


def check(root):
    """[(variable, message)] for every variable a compose file renders empty over a shipped value."""
    values = shipped(root)
    problems = []
    for compose in sorted(root.glob("docker-compose*.yml")):
        for m in EMPTY_ENV.finditer(compose.read_text(encoding="utf-8")):
            name = m.group(1)
            if name in values:
                prop, value, where = values[name]
                problems.append((name, f"{compose.name}: {name} is rendered empty, which removes {prop}={value} "
                                       f"({where}); write it out: {name}: ${{{name}:-{value}}}"))
    return problems


def selftest():
    with tempfile.TemporaryDirectory() as d:
        root = pathlib.Path(d)
        res = root / "mod/src/main/resources"
        java = root / "mod/src/main/java/x"
        res.mkdir(parents=True)
        java.mkdir(parents=True)
        (res / "application.properties").write_text(
            "a.b-c.rpm=600\n%prod.p.q=1\n%dev.d.e=1\nmail.user=${MAIL_USER:}\n# z.z=1\n"
            "llm.url=${LLM_URL:http://localhost:1}\n"
            "o.v=5\n%prod.o.v=${O_V:}\n%prod,staging.m.s=2\nmail.host=${smtp.host:localhost}\n", encoding="utf-8")
        (java / "F.java").write_text(
            '@ConfigProperty(\n    defaultValue = "5",\n    name = "f.factor") int f;\n'
            '@ConfigProperty(name = "g.opt") Optional<String> g;\n'
            '@ConfigProperty(defaultValue = "ua (a@b)", name = "u.agent") String u;\n', encoding="utf-8")
        (root / "docker-compose.selfhost.yml").write_text(
            "services:\n  hub:\n    environment:\n"
            "      A_B_C_RPM: ${A_B_C_RPM:-}\n"        # shipped 600: refused
            "      P_Q: \"${P_Q:-}\"\n"                 # %prod ships it: refused
            "      F_FACTOR: ${F_FACTOR-}\n"            # a @ConfigProperty default: refused
            "      D_E: ${D_E:-}\n"                     # %dev only: fine
            "      MAIL_USER: ${MAIL_USER:-}\n"         # ships an empty expression: fine
            "      G_OPT: ${G_OPT:-}\n"                 # no default: fine
            "      LLM_URL: ${LLM_URL:-}\n"             # beats the expression's default: refused
            "      A_B_C_RPM_2: ${A_B_C_RPM:-600}\n"    # written out: fine
            "      O_V: ${O_V:-}\n"                     # %prod ships nothing over the base 5: fine
            "      M_S: ${M_S}  # bare, a comment\n"    # %prod,staging ships it: refused
            "      SMTP_HOST: '${SMTP_HOST:-}'\n"       # a dotted reference's default: refused
            "      - LIST_FORM=${A_B_C_RPM:-}\n"        # list form, not a shipped name: fine
            "      U_AGENT: \"\"\n",                   # a literal empty over a default holding `)`: refused
            encoding="utf-8")
        (root / "docker-compose.list.yml").write_text(
            "services:\n  hub:\n    environment:\n      - A_B_C_RPM=${A_B_C_RPM:-}\n"   # refused
            "      - F_FACTOR=\n      - P_Q=1\n", encoding="utf-8")                      # refused, fine
        got = sorted(name for name, _ in check(root))
        want = ["A_B_C_RPM", "A_B_C_RPM", "F_FACTOR", "F_FACTOR", "LLM_URL", "M_S", "P_Q", "SMTP_HOST", "U_AGENT"]
        if got != want:
            print(f"selftest FAILED: reported {got}, want {want}", file=sys.stderr)
            return 1
    print("selftest ok")
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", type=pathlib.Path, default=pathlib.Path(__file__).resolve().parent.parent)
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()
    if args.selftest:
        return selftest()
    problems = check(args.root)
    for _, message in problems:
        print(message, file=sys.stderr)
    if problems:
        return 1
    print("compose hands the hub no empty value over a shipped property")
    return 0


if __name__ == "__main__":
    sys.exit(main())
