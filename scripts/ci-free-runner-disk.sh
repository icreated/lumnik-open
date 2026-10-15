#!/usr/bin/env bash
# Frees disk on a GitHub-hosted ubuntu runner before the nightly's container-backed ITs pull their images.
#
# Why: the nightly's `verify` ended with 11 GB free on 2026-10-05/07 and 3.1 GB (96 %) on 10-09 and 10-11,
# the first nights after the Oracle image joined the suite (#861). Every failure since is a container IT
# (S3, Kafka, Oracle, SFTP) — a full disk shows as a broker or a fork dying, never as the word "disk"
# (see the "Runner disk margin" step in nightly-verify.yml).
#
# What: removes toolchains the Java build never reads (.NET, the Android SDK, GHC, CodeQL bundles).
# Never the JDK, Maven, Go or Docker. Best-effort: a path that is absent is skipped, a failed removal is
# reported and does not fail the job — the point is margin, not a gate.
#
# FREE_DISK_ROOT and SUDO exist so the script can be run against a throwaway tree (see its self-test in
# the PR that added it); on a runner both keep their defaults.
set -uo pipefail

ROOT="${FREE_DISK_ROOT:-}"
SUDO="${SUDO-sudo}"

PATHS=(
    /usr/share/dotnet
    /usr/local/lib/android
    /opt/ghc
    /usr/local/.ghcup
    /opt/hostedtoolcache/CodeQL
)

margin() { df -h "${ROOT:-/}" | tail -1; }

echo "disk before: $(margin)"
for p in "${PATHS[@]}"; do
    target="${ROOT}${p}"
    if [ -e "$target" ]; then
        size="$(du -sh "$target" 2>/dev/null | cut -f1)"
        if $SUDO rm -rf "$target"; then
            echo "removed $p (${size:-?})"
        else
            echo "could not remove $p"
        fi
    fi
done
echo "disk after:  $(margin)"
