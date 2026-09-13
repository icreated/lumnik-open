#!/bin/sh
# Manual lane: the things about `lm`'s secret prompts that no Go test can reach, because Go has no
# pseudo-terminal without a new dependency and `go test` runs without a tty.
#
#   A. `lm source jdbc test` prompts at a keyboard, and what is typed never appears;
#   B. Ctrl+C at that prompt leaves the terminal exactly as it was found;
#   C. `lm secret set NAME` prompts the same way — the form that replaces typing a secret as an
#      argument, where it lands in `ps` and in the shell history;
#   D. the prompt still reaches the caller when stderr is redirected, because it is written to the
#      terminal rather than to a stream. Getting this wrong reproduced the original defect: echo
#      off, no prompt, a silent block.
#
# (B) was a real defect: term.ReadPassword clears ECHO and restores it from a DEFERRED call, which
# a signal never reaches. Before the guard in terminal.go, run B ended on `-echo` and the caller
# was left typing blind until `stty sane`.
#
# A, C and D assert on the transcript AFTER the prompt line, not on the whole file, and that is the
# claim rather than a workaround: a pty echoes with ECHO ON until the process takes it over, so
# bytes delivered before the prompt exists are echoed by the terminal driver before any of our
# code runs. What we own — and what these runs check — is that nothing appears once the prompt is
# up. (Waiting on the transcript instead was tried and deadlocks: BSD `script` flushes it at exit.)
#
# Hermetic: HOME holds a context pointing at a closed port, so every run reaches its prompt and
# then fails to connect. Nothing touches a real hub — in particular `secret set` never creates
# anything. The context must exist: the commands resolve it BEFORE prompting, on purpose.
#
# Not in CI — it needs a tty allocator. Run it after touching a prompt:  cd lm && make pty

set -e
cd "$(dirname "$0")/../.."
[ -x ./lm ] || { echo "build first: make build"; exit 2; }

# BSD (macOS) and util-linux spell `script` differently; both hand the child a real pty and log
# what it writes to the file named first. util-linux's -c takes ONE string, so that branch cannot
# preserve argv — nothing passed below contains a space, which is what makes the asymmetry safe.
if script -q /dev/null true >/dev/null 2>&1; then
  pty() { log="$1"; shift; script -q "$log" "$@"; }
else
  pty() { log="$1"; shift; script -qec "$*" "$log"; }
fi

LM="$(pwd)/lm"
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
HOME="$WORK"; export HOME
mkdir -p "$WORK/.lm"
printf 'current-context: probe\ncontexts:\n  probe:\n    server: http://127.0.0.1:1\n    token: probe\n' > "$WORK/.lm/config.yaml"
# What the probe types at the prompt, self-describing ON PURPOSE: the open edition's gitleaks
# scan cannot know a string is a fixture, and a random-looking one fails the publish as a leaked
# key. The claim here needs a distinctive string, not a secret-shaped one.
SECRET=typed-by-the-probe-not-a-password
JDBC="source jdbc test --url jdbc:postgresql://nowhere:1/d --user probe_user"
fail=0

"$LM" --help >/dev/null 2>&1 || true # page the binary in, so the typing below is not racing a cold start

# perl, not `sleep`. Swapping the two here was measured to break every run deterministically —
# three failures with `sleep`, then the same file with only this line changed passing every time —
# with `script` exiting before the prompt. The mechanism is not established, so the reason recorded
# is the measurement, not a story about it. perl also gives sub-second waits portably.
typeIn() { perl -e "select(undef,undef,undef,2)"; printf '%s\n' "$SECRET"; perl -e "select(undef,undef,undef,0.5)"; }
want()   { grep -q "$2" "$1" || { echo "FAIL [$3]: $4"; fail=1; }; }
# afterPrompt: the transcript from the prompt line onward — the window this lane makes claims about.
afterPrompt() { tr -d '\r' < "$1" | sed -n "/$2/,\$p"; }

probe() { # <tag> <log> <prompt-pattern> <lm args...>
  tag="$1"; plog="$2"; pat="$3"; shift 3
  typeIn | pty "$plog" "$@" >/dev/null 2>&1 || true
  want "$plog" "$pat" "$tag" "no prompt matching '$pat'"
  if afterPrompt "$plog" "$pat" | grep -q "$SECRET"; then
    echo "FAIL [$tag]: what was typed at the prompt was echoed"; fail=1
  fi
}

probe A "$WORK/a.log" 'Password for probe_user: ' "$LM" $JDBC
probe C "$WORK/c.log" 'Value for PROBE_NAME: '    "$LM" secret set PROBE_NAME

# D — stderr thrown away. The prompt is the terminal's, so it must still be on screen; a stream
# would have swallowed it and left the caller typing blind into an echo-less tty.
cat > "$WORK/redirected.sh" <<INNER
#!/bin/sh
exec 2>/dev/null
exec $LM $JDBC
INNER
chmod +x "$WORK/redirected.sh"
probe D "$WORK/d.log" 'Password for probe_user: ' "$WORK/redirected.sh"

# B — interrupted at the prompt. The inner script waits for ECHO to actually go off, so the signal
# lands while the prompt owns the terminal, which is the only moment that proves anything.
cat > "$WORK/interrupt.sh" <<INNER
#!/bin/sh
echoState() { stty -a | tr ' ' '\n' | grep -x -- '-\{0,1\}echo'; }
printf 'ECHO_BEFORE=%s\n' "\$(echoState)"
$LM $JDBC < /dev/tty &
P=\$!
n=0
while [ "\$(echoState)" = "echo" ]; do
  n=\$((n + 1)); [ \$n -gt 100 ] && break
  sleep 0.1
done
printf 'ECHO_AT_PROMPT=%s\n' "\$(echoState)"
kill -INT \$P 2>/dev/null
sleep 0.5
kill -KILL \$P 2>/dev/null
printf 'ECHO_AFTER=%s\n' "\$(echoState)"
INNER
chmod +x "$WORK/interrupt.sh"
# Something must hold the pty's input open while the inner script works, and it must not WRITE:
# a newline here is read as an empty password, the prompt returns at once and the interrupt lands
# on nothing — which is what ECHO_AT_PROMPT below caught when this was a `printf` loop. So it is a
# sleeper, sized to the inner script (a bounded poll that normally ends in ~0.3s) rather than to
# its 10s ceiling. A slow run is not silently passed: ECHO_AT_PROMPT fails it.
sleep 3 | pty "$WORK/b.log" "$WORK/interrupt.sh" >/dev/null 2>&1 || true
want "$WORK/b.log" 'ECHO_BEFORE=echo'     B "the probe itself started without echo"
want "$WORK/b.log" 'ECHO_AT_PROMPT=-echo' B "the prompt never took the terminal — the interrupt proved nothing"
want "$WORK/b.log" 'ECHO_AFTER=echo'      B "Ctrl+C at the prompt left the terminal echoless"

[ $fail -eq 0 ] && echo "ok — prompts shown (stderr redirected too), nothing echoed, terminal restored after Ctrl+C"
exit $fail
