# shellcheck shell=bash
# bearer <token> — prints the Authorization header for `curl -H @<(bearer "$tok")`. curl reads the header from a file
# descriptor, so the token never rides a command line: `ps` shows every process's arguments to every user of the host.
# printf is a shell builtin: the token reaches no process's arguments. A pipe is drained once, so build the header for each
# call, never once for a retry loop. Sourced by deploy/selfhost/up.sh (for itself and realm.sh) and deploy/helm/up.sh.
bearer() { printf 'Authorization: Bearer %s\n' "$1"; }
