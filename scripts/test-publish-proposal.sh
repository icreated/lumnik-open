#!/usr/bin/env bash
# Offline test of scripts/publish-proposal-catalogue.sh: throwaway bare remotes stand in for the
# monorepo's and the site's `origin`, so the script's real guards run and nothing real is touched.
# The current scripts/ are copied into the throwaway monorepo, so this tests the working tree.
#
# Scenarios: a fresh publication · an identical re-run (a no-op that leaves the site as found) · a
# corpus-only change · a monorepo behind origin/main (refused) · a commit that fails (the site must
# come back as it was found, nothing staged, no stray branch).
#
# Run:  scripts/test-publish-proposal.sh [path-to-lumnik-site-checkout]   (default: ../lumnik-site)
# Exit 0 = every scenario held.
set -u

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SITE_SRC=${1:-"$ROOT/../lumnik-site"}
[ -d "$SITE_SRC/.git" ] || { echo "no lumnik-site checkout at $SITE_SRC" >&2; exit 2; }

R=$(mktemp -d)
trap 'rm -rf "$R"' EXIT
bad=0
# A setup step that fails must stop the run: a harness that goes on would test the committed scripts
# (HEAD) while claiming to test the working tree, and report a PASS it did not earn.
die() { echo "harness setup failed: $*" >&2; exit 2; }
SCRIPTS="check-proposal-catalogue.py check-proposal-corpus.py proposal-site-index.py publish-proposal-catalogue.sh"
check() { # check <name> <expected> <actual>
  if [ "$2" = "$3" ]; then echo "  ok   $1"; else echo "  FAIL $1 (expected '$2', got '$3')"; bad=$((bad + 1)); fi
}

fresh_site() {
  rm -rf "$R/site.git" "$R/site"
  git clone -q --bare "$SITE_SRC" "$R/site.git" || die "cannot clone the site"
  git clone -q "$R/site.git" "$R/site" || die "cannot clone the throwaway site"
  git -C "$R/site" config user.email t@t && git -C "$R/site" config user.name t || die "git config"
}

cd "$R" || exit 2
git init -q --bare mono.git || die "git init"
git -C "$ROOT" push -q "$R/mono.git" HEAD:refs/heads/main || die "cannot push HEAD to the throwaway remote"
git clone -q "$R/mono.git" mono || die "cannot clone the throwaway monorepo"
git -C mono config user.email t@t && git -C mono config user.name t || die "git config"
for f in $SCRIPTS; do cp "$ROOT/scripts/$f" "mono/scripts/$f" || die "cannot copy scripts/$f"; done
git -C mono add -A || die "git add"
git -C mono diff --cached --quiet || git -C mono commit -q -m "throwaway" || die "git commit"
git -C mono push -q origin HEAD:main || die "git push"
for f in $SCRIPTS; do cmp -s "$ROOT/scripts/$f" "mono/scripts/$f" || die "the throwaway monorepo does not hold the working tree's scripts/$f"; done
[ "$(git -C mono rev-parse HEAD)" = "$(git -C mono rev-parse origin/main)" ] || die "the throwaway monorepo is not at its origin/main"
publish() { (cd "$R/mono" && bash scripts/publish-proposal-catalogue.sh "$R/site" >"$R/out" 2>&1); echo $?; }
branch() { git -C "$R/site" rev-parse --abbrev-ref HEAD; }
VERSION=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["catalogue"])' "$ROOT/lumnik-hub/src/main/resources/proposal/catalogue.json")

echo "== 1 fresh publication"
fresh_site
check "exits 0" 0 "$(publish)"
check "the site is left on the catalogue branch" "catalogue/$VERSION" "$(branch)"
check "the commit carries the catalogue, the index and the corpus" 1 \
  "$(git -C "$R/site" show --name-only --format= HEAD | grep -c -e "^catalogue/$VERSION.json$")"
check "the index is committed" 1 "$(git -C "$R/site" show --name-only --format= HEAD | grep -c '^catalogue-index.json$')"
check "the fixtures are marked -text" 1 "$(git -C "$R/site" show --name-only --format= HEAD | grep -c '^tests/fixtures/.gitattributes$')"
attr() { git -C "$R/site" check-attr text -- "$1" | sed 's/.*: text: //'; }
check "git sees a vendored fixture as -text (bytes as committed)" unset "$(attr tests/fixtures/proposal/valid/minimal.md)"
check "...but not an unrelated fixture suite under tests/fixtures/" unspecified "$(attr tests/fixtures/other-suite/case.md)"

echo "== 2 identical re-run is a no-op that leaves the site as found"
git -C "$R/site" push -q origin "catalogue/$VERSION:main"; git -C "$R/site" switch -q main; git -C "$R/site" pull -q origin main
check "exits 0" 0 "$(publish)"
check "the site is still on main" main "$(branch)"
check "no corpus branch was left behind" 0 "$(git -C "$R/site" branch --list 'corpus/*' | wc -l | tr -d ' ')"

echo "== 3 a corpus-only change goes on its own branch"
(cd "$R/mono" && echo "" >> lumnik-hub/src/test/resources/proposal/fixtures/valid/no-body.md && python3 scripts/check-proposal-corpus.py --write >/dev/null && git add -A && git commit -qm t && git push -q origin HEAD:main) || die "scenario 3 setup"
check "exits 0" 0 "$(publish)"
case "$(branch)" in corpus/*) check "a corpus/<digest> branch" ok ok ;; *) check "a corpus/<digest> branch" "corpus/*" "$(branch)" ;; esac
check "only the fixture and its digest changed" 2 "$(git -C "$R/site" show --name-only --format= HEAD | wc -l | tr -d ' ')"
git -C "$R/site" switch -q main

echo "== 4 a monorepo behind origin/main is refused"
(cd "$R/mono" && git reset -q --hard HEAD~1) || die "scenario 4 setup"
check "exits 1" 1 "$(publish)"
check "the site never left main" main "$(branch)"
(cd "$R/mono" && git fetch -q origin && git reset -q --hard origin/main) || die "scenario 4 teardown"
git -C "$R/site" branch -q -D "$(git -C "$R/site" branch --list 'corpus/*' | tr -d ' *')" 2>/dev/null

echo "== 5 a failing commit leaves the site as it was found (review of #805)"
fresh_site
printf '#!/bin/sh\necho "hook: commit refused" >&2\nexit 1\n' > "$R/site/.git/hooks/pre-commit"; chmod +x "$R/site/.git/hooks/pre-commit"
rc=$(publish)
check "exits non-zero" 1 "$([ "$rc" != 0 ] && echo 1 || echo 0)"
check "it failed AT THE COMMIT (the hook ran), not at an earlier guard" 1 "$(grep -c 'commit refused' "$R/out")"
check "the site is back on main" main "$(branch)"
check "nothing is staged" 0 "$(git -C "$R/site" diff --cached --name-only | wc -l | tr -d ' ')"
check "no untracked leftovers" 0 "$(git -C "$R/site" status --porcelain | wc -l | tr -d ' ')"
check "the new branch is gone" main "$(git -C "$R/site" branch --list | tr -d ' *' | tr '\n' ' ' | sed 's/ $//')"

echo "== 7 a detached HEAD is restored exactly, not replaced by a branch called HEAD"
fresh_site
# detached on an OLDER commit than origin/main: restoring to the wrong place must show
git -C "$R/site" checkout -q --detach HEAD~1; DETACHED=$(git -C "$R/site" rev-parse HEAD)
check "(the detached commit differs from origin/main)" 1 "$([ "$DETACHED" != "$(git -C "$R/site" rev-parse origin/main)" ] && echo 1 || echo 0)"
printf '#!/bin/sh\necho "hook: commit refused" >&2\nexit 1\n' > "$R/site/.git/hooks/pre-commit"; chmod +x "$R/site/.git/hooks/pre-commit"
rc=$(publish)
check "exits non-zero" 1 "$([ "$rc" != 0 ] && echo 1 || echo 0)"
check "it failed AT THE COMMIT (the hook ran), not at an earlier guard" 1 "$(grep -c 'commit refused' "$R/out")"
check "the site is detached again, at the commit it was found on" "$DETACHED" "$(git -C "$R/site" rev-parse HEAD)"
check "HEAD is detached" HEAD "$(branch)"
check "nothing is staged" 0 "$(git -C "$R/site" diff --cached --name-only | wc -l | tr -d ' ')"
check "the new branch is gone" 1 "$(git -C "$R/site" branch --list | grep -vc 'HEAD detached')"

echo "== 8 a branch that already exists locally is refused with a clear message and left alone"
fresh_site
git -C "$R/site" branch "catalogue/$VERSION"; EXISTING=$(git -C "$R/site" rev-parse "catalogue/$VERSION")
check "exits 1" 1 "$(publish)"
check "the script's own message names the branch (not git's raw fatal)" 1 "$(grep -c "refused: the site checkout already has a local branch catalogue/$VERSION" "$R/out")"
check "the site never left main" main "$(branch)"
check "the existing branch was not touched" "$EXISTING" "$(git -C "$R/site" rev-parse "catalogue/$VERSION")"
check "nothing is staged" 0 "$(git -C "$R/site" diff --cached --name-only | wc -l | tr -d ' ')"

# Last on purpose: it breaks the throwaway monorepo's catalogue, and a later scenario would inherit
# the refusal and test nothing (it did, when this one ran before the detached-HEAD scenario).
echo "== 6 a refusal before any branch leaves the site untouched"
fresh_site
(cd "$R/mono" && python3 - <<'PY'
import json, pathlib
p = pathlib.Path("lumnik-hub/src/main/resources/proposal/catalogue.json")
c = json.loads(p.read_text()); c["questions"][0]["id"] = "aim"; p.write_text(json.dumps(c, ensure_ascii=False))
PY
git add -A && git commit -qm "break the goal" && git push -q origin HEAD:main) || die "scenario 6 setup"
check "exits 1 (the catalogue check refuses)" 1 "$(publish)"
check "nothing is staged" 0 "$(git -C "$R/site" diff --cached --name-only | wc -l | tr -d ' ')"
check "the site never left main" main "$(branch)"

echo
[ "$bad" = 0 ] && echo "publish rehearsal PASSED" || echo "publish rehearsal FAILED ($bad)"
[ "$bad" = 0 ]
