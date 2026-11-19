#!/usr/bin/env bash
# #1998: the deploy scripts, the chart and the compose files never put a secret on a command line. `ps` shows every process's arguments to every user of
# the host. A bearer token rides a file descriptor (`-H @<(bearer "$t")`,
# deploy/selfhost/bearer.sh), a password rides stdin, an env-file or a values file. `loadtest/` is out of scope: it
# drives a throwaway stack on the developer's own machine, never an install.
# --selftest plants each refused form in a scratch tree and checks the guard names it.
set -euo pipefail
cd "$(dirname "$0")/.."

# Each refused form, and what to do instead. A grep holds the forms this repository has used, not every way a secret can
# reach a command line.
PATTERNS=(
  'Bearer \$'
  '--set(-string|-literal|-json)? [A-Za-z0-9_.]*([Pp]assword|[Ss]ecret|[Tt]oken|[Kk]ey)='
  "curl[^|]*-u ['\"]?[A-Za-z0-9_]*:\\$"
  '-d[^|]*([Pp]assword|[Ss]ecret)=\$'
  'create secret[^|]*--from-literal'
)
WHYS=(
  "a bearer token on curl's argv: use -H @<(bearer \"\$tok\")"
  'a secret in a helm --set: write it to a values file'
  "a credential in curl -u: write it to curl -K - on stdin, as 'user = \"name:pw\"'"
  'a secret in a curl form field: send it on stdin, --data-urlencode password@-'
  'a secret in kubectl --from-literal: use a heredoc or an env-file'
)

# scan <path>... — every refused form in the shell and YAML files under each path, comments left out. Prints them; 1 when
# any.
scan() {
  local fail=0 i hits
  for i in "${!PATTERNS[@]}"; do
    hits="$(grep -rnE --include='*.sh' --include='*.yaml' --include='*.yml' -- "${PATTERNS[$i]}" "$@" \
      | grep -vE '^[^:]+:[0-9]+:[[:space:]]*#' || true)"
    if [ -n "$hits" ]; then echo "FAIL ${WHYS[$i]}:"; echo "$hits" | sed 's/^/  /'; fail=1; fi
  done
  return "$fail"
}

if [ "${1:-}" = "--selftest" ]; then
  work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
  ok=0
  plant() {  # <line> <expect: refused|kept>
    printf '%s\n' "$1" > "$work/x.sh"
    if scan "$work" >/dev/null; then got=kept; else got=refused; fi
    if [ "$got" = "$2" ]; then echo "ok   $2: $1"; else echo "FAIL expected $2, got $got: $1"; ok=1; fi
  }
  plant 'curl -H "Authorization: Bearer $tok" http://x'           refused
  plant 'helm upgrade r c --set-string smtp.password="$P"'        refused
  plant 'helm upgrade r c --set secrets.cryptoKey="$K"'           refused
  plant 'kubectl create secret generic s --from-literal=A="$A"'   refused
  plant 'curl -fsS -u "admin:$PW" https://x'                     refused
  plant 'curl -d grant_type=password -d "password=$PW" http://x'  refused
  plant 'curl --data "client_secret=$S" http://x'                 refused
  plant 'kubectl create configmap m --from-literal=mode=demo'     kept
  plant 'curl -d grant_type=password --data-urlencode password@- http://x' kept
  plant 'curl -H @<(bearer "$tok") http://x'                      kept
  plant '  # never -H "Authorization: Bearer $tok" on argv'      kept
  plant 'helm upgrade r c --set smtp.host="$H"'                   kept
  exit "$ok"
fi

scan deploy docker-compose*.yml && echo "ok   no deploy script, chart or compose file puts a secret on a command line"
