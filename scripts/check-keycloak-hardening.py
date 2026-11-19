#!/usr/bin/env python3
"""The shipped Keycloak keeps its security settings (#1997).

What it holds, on every shipped realm (infra/keycloak/*.json):
- no client takes a password grant: the PWA's login page and lm's device flow (`lm token` for a script) are the
  ways in; `lumnik-smoke`, the throwaway realms' password client, is never shipped;
- an access token lives 5 minutes at most: a removed role, scope or account stops within 5 minutes even when the hub
  validates tokens locally (the PWA renews before a call, #2036);
- login and admin events are stored (admin events without representations), and a password policy is set;
- `tenant_id` and `user_id` are declared attributes that only an admin may see or edit, and the unmanaged-attribute
  policy is never ENABLED: under ENABLED a user rewrites their own tenant_id from the account console.

And around it:
- a compose file imports one realm file, never the directory (two files declare realm `lumnik`);
- no installer switches the policy to ENABLED.

    python3 scripts/check-keycloak-hardening.py [--root DIR]
    python3 scripts/check-keycloak-hardening.py --selftest
"""
import argparse
import json
import pathlib
import re
import sys
import tempfile

MAX_ACCESS_TOKEN_SECONDS = 300
PUBLIC_CLIENTS = ("lumnik-spa", "lm-cli")
MIN_PASSWORD_LENGTH = 12
ADMIN_ONLY = ("tenant_id", "user_id")
POLICY_COMPONENT = "org.keycloak.userprofile.UserProfileProvider"
# p['unmanagedAttributePolicy']='ENABLED', "unmanagedAttributePolicy": "ENABLED", kcadm's -s unmanagedAttributePolicy=ENABLED
# — never a comparison (==)
SETS_ENABLED = re.compile(r"""unmanagedAttributePolicy['"]?\]?\s*[=:]\s*['"]?ENABLED""")


def realm_problems(name, realm):
    out = []
    # No client takes a password: the PWA's login page, lm's device flow (lm token for scripts). lumnik-smoke, the
    # throwaway realms' password client (scripts/lib/smoke-client.sh), is never shipped.
    ids = [c.get("clientId") for c in realm.get("clients", [])]
    for client_id in PUBLIC_CLIENTS:
        if client_id not in ids:
            out.append(f"{name}: no {client_id} client")
    for c in realm.get("clients", []):
        if c.get("clientId") == "lumnik-smoke":
            out.append(f"{name}: ships lumnik-smoke, the throwaway realms' password client")
        elif c.get("directAccessGrantsEnabled") is True:
            out.append(f"{name}: {c.get('clientId')} takes the password grant (directAccessGrantsEnabled must be false)")
    if not 0 < (realm.get("accessTokenLifespan") or 0) <= MAX_ACCESS_TOKEN_SECONDS:
        out.append(f"{name}: accessTokenLifespan must be 1..{MAX_ACCESS_TOKEN_SECONDS}, not {realm.get('accessTokenLifespan')}")
    for c in realm.get("clients", []):
        # a client's own lifespan wins over the realm's
        own = (c.get("attributes") or {}).get("access.token.lifespan")
        if own in (None, ""):
            continue  # the realm's lifespan
        if not str(own).isdigit() or not 0 < int(own) <= MAX_ACCESS_TOKEN_SECONDS:
            out.append(f"{name}: client {c.get('clientId')} sets access.token.lifespan {own!r}: "
                       f"a number of seconds, 1..{MAX_ACCESS_TOKEN_SECONDS}")
    if realm.get("eventsEnabled") is not True or not (realm.get("eventsExpiration") or 0) > 0:
        out.append(f"{name}: login events must be stored, with an expiration (eventsEnabled, eventsExpiration)")
    if realm.get("adminEventsEnabled") is not True or realm.get("adminEventsDetailsEnabled") is not False:
        out.append(f"{name}: admin events must be stored, without representations")
    policy = realm.get("passwordPolicy") or ""
    length = re.search(r"length\((\d+)\)", policy)
    if not length or int(length.group(1)) < MIN_PASSWORD_LENGTH or "notUsername" not in policy or "notEmail" not in policy:
        out.append(f"{name}: passwordPolicy needs length({MIN_PASSWORD_LENGTH}) or more, notUsername and notEmail, not '{policy}'")
    out += profile_problems(name, realm)
    return out


def profile_problems(name, realm):
    components = (realm.get("components") or {}).get(POLICY_COMPONENT) or []
    configs = [c.get("config", {}).get("kc.user.profile.config") for c in components]
    if len(configs) != 1 or not configs[0]:
        return [f"{name}: the user profile must be declared once ({POLICY_COMPONENT})"]
    profile = json.loads(configs[0][0])
    out = []
    if profile.get("unmanagedAttributePolicy") == "ENABLED":
        out.append(f"{name}: unmanagedAttributePolicy ENABLED lets a user edit their own attributes")
    declared = {a["name"]: a for a in profile.get("attributes", [])}
    for attr in ADMIN_ONLY:
        perms = declared.get(attr, {}).get("permissions")
        if perms != {"view": ["admin"], "edit": ["admin"]}:
            out.append(f"{name}: {attr} must be declared, seen and edited by the admin only, not {perms}")
    return out


def check(root):
    out = []
    realms = sorted(root.glob("infra/keycloak/*.json"))
    if not realms:
        out.append("no realm under infra/keycloak/")
    for f in realms:
        out += realm_problems(f.relative_to(root), json.loads(f.read_text(encoding="utf-8")))
    for compose in sorted(root.glob("docker-compose*.yml")):
        for line in compose.read_text(encoding="utf-8").splitlines():
            if re.search(r"infra/keycloak/?:", line):
                out.append(f"{compose.name}: mounts the realm directory; mount lumnik-realm.json alone")
    for script in sorted([*root.glob("deploy/**/*.sh"), *root.glob("loadtest/**/*.sh")]):
        if SETS_ENABLED.search(script.read_text(encoding="utf-8")):
            out.append(f"{script.relative_to(root)}: switches unmanagedAttributePolicy to ENABLED")
    return out


def good_realm():
    profile = {"attributes": [{"name": a, "permissions": {"view": ["admin"], "edit": ["admin"]}} for a in ADMIN_ONLY]}
    return {
        "clients": [{"clientId": "lumnik-spa", "directAccessGrantsEnabled": False},
                    {"clientId": "lm-cli", "directAccessGrantsEnabled": False}],
        "accessTokenLifespan": 300,
        "eventsEnabled": True, "eventsExpiration": 2592000,
        "adminEventsEnabled": True, "adminEventsDetailsEnabled": False,
        "passwordPolicy": "length(12) and notUsername and notEmail",
        "components": {POLICY_COMPONENT: [{"config": {"kc.user.profile.config": [json.dumps(profile)]}}]},
    }


def with_profile(change):
    """A realm breaker that edits the declared user profile."""
    def brk(realm):
        config = realm["components"][POLICY_COMPONENT][0]["config"]
        profile = json.loads(config["kc.user.profile.config"][0])
        change(profile)
        config["kc.user.profile.config"] = [json.dumps(profile)]
    return brk


def selftest():
    ok = not realm_problems("good", good_realm())
    breaks = {
        "password grant": lambda r: r["clients"][0].update(directAccessGrantsEnabled=True),
        "password grant on lm-cli": lambda r: r["clients"][1].update(directAccessGrantsEnabled=True),
        "password grant on another client": lambda r: r["clients"].append({"clientId": "x", "directAccessGrantsEnabled": True}),
        "lumnik-smoke shipped": lambda r: r["clients"].append({"clientId": "lumnik-smoke", "directAccessGrantsEnabled": False}),
        "hour token": lambda r: r.update(accessTokenLifespan=3600),
        "a client's own hour": lambda r: r["clients"][0].setdefault("attributes", {}).update({"access.token.lifespan": "3600"}),
        "a client's lifespan that is not a number": lambda r: r["clients"][0].setdefault("attributes", {}).update(
            {"access.token.lifespan": "300s"}),
        "no events": lambda r: r.update(eventsEnabled=False),
        "admin event details": lambda r: r.update(adminEventsDetailsEnabled=True),
        "short password": lambda r: r.update(passwordPolicy="length(8) and notUsername and notEmail"),
        "email as password": lambda r: r.update(passwordPolicy="length(12) and notUsername"),
        "no profile": lambda r: r.update(components={}),
        "user edits tenant": with_profile(lambda p: p["attributes"][0]["permissions"].update(edit=["admin", "user"])),
        "policy enabled": with_profile(lambda p: p.update(unmanagedAttributePolicy="ENABLED")),
    }
    for label, brk in breaks.items():
        r = good_realm()
        brk(r)
        if not realm_problems(label, r):
            print(f"selftest FAILED: '{label}' was not reported", file=sys.stderr)
            ok = False
    with tempfile.TemporaryDirectory() as d:
        root = pathlib.Path(d)

        def write(rel, text):
            (root / rel).parent.mkdir(parents=True, exist_ok=True)
            (root / rel).write_text(text, encoding="utf-8")

        write("infra/keycloak/lumnik-realm.json", json.dumps(good_realm()))
        write("docker-compose.yml", "      - ./infra/keycloak/lumnik-realm.json:/opt/keycloak/data/import/lumnik-realm.json:ro\n")
        write("deploy/selfhost/realm.sh", 'if p.get("unmanagedAttributePolicy") == "ENABLED":\n')
        problems = check(root)
        if problems:
            print(f"selftest FAILED: a good tree reported {problems}", file=sys.stderr)
            ok = False
        write("docker-compose.yml", "      - ./infra/keycloak:/opt/keycloak/data/import:ro\n")
        write("deploy/helm/up.sh", "p['unmanagedAttributePolicy']='ENABLED'\n")
        write("deploy/selfhost/heal.sh", 'echo \'{"unmanagedAttributePolicy": "ENABLED"}\'\n')
        write("loadtest/kc.sh", "kcadm.sh update users/profile -r lumnik -s unmanagedAttributePolicy=ENABLED\n")
        problems = check(root)
        wanted = ("docker-compose.yml:", "deploy/helm/up.sh:", "deploy/selfhost/heal.sh:", "loadtest/kc.sh:")
        if sorted(p.split(" ")[0] for p in problems) != sorted(wanted):
            print(f"selftest FAILED: a bad tree reported {problems}", file=sys.stderr)
            ok = False
    print("selftest ok" if ok else "selftest FAILED")
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", type=pathlib.Path, default=pathlib.Path(__file__).resolve().parent.parent)
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()
    if args.selftest:
        return selftest()
    problems = check(args.root)
    for p in problems:
        print(p, file=sys.stderr)
    if problems:
        return 1
    print("the shipped Keycloak keeps its security settings")
    return 0


if __name__ == "__main__":
    sys.exit(main())
