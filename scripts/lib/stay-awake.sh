# shellcheck shell=bash
# stay_awake "$0" "$@" — re-runs the calling script under `caffeinate -i` on macOS, so the machine does not sleep while
# it runs (#2079): a release proof left overnight was suspended 11 minutes in the middle of an OTK export wait, and the
# 10-minute token it held had expired on wake (a 401 the run could not explain). Elsewhere, and when already awake
# (a script calling another), it does nothing. It holds off idle sleep only: a closed lid still sleeps, so an
# overnight run needs the lid open (or an external display) and AC power.
stay_awake() {
  [ -n "${LUMNIK_STAY_AWAKE:-}" ] && return 0
  [ "$(uname -s)" = Darwin ] || return 0   # another system's caffeinate takes other flags
  command -v caffeinate >/dev/null 2>&1 || return 0
  export LUMNIK_STAY_AWAKE=1
  exec caffeinate -i bash "$@"
}
