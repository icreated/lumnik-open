#!/usr/bin/env bash
# Witnesses that the OPEN edition boots: lumnik-hub built without lumnik-rag and
# lumnik-llm-langchain — the edition the public repo would ship (publication ritual, Act 1).
#
# Why this boots instead of only building: the two things that broke when this was first tried
# fail at DIFFERENT moments. An unsatisfied @Judge ChatPort fails Arc validation at BUILD time; a
# role naming a permission no module declares fails at STARTUP. A build-only guard would have
# reported green on the second one — which is exactly how it stayed hidden.
#
# Needs Docker and Java 21. Uses its own container and port, and removes them, so it never
# touches a running dev stack. Run from the repo root.

set -euo pipefail
cd "$(dirname "$0")/.."

PG_NAME=lumnik-open-edition-witness
PG_PORT=${OPEN_EDITION_PG_PORT:-55432}
HTTP_PORT=${OPEN_EDITION_HTTP_PORT:-8099}
LOG=$(mktemp -t open-edition-boot.XXXXXX)
APP_PID=""
BOOT_CWD=""
# Resolve the JVM from JAVA_HOME rather than from PATH: a machine with an older default `java`
# would fail with UnsupportedClassVersionError, which reads like a seam problem and is not one.
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"

cleanup() {
  [ -n "$APP_PID" ] && kill "$APP_PID" 2>/dev/null || true
  docker rm -f "$PG_NAME" >/dev/null 2>&1 || true
  [ -n "${BOOT_CWD:-}" ] && rm -rf "$BOOT_CWD" || true
}
trap cleanup EXIT

echo "=== check-open-edition-boots ==="

echo "→ building the open edition (-DopenEdition), test sources included"
# -DskipTests, NOT -Dmaven.test.skip: the tests are COMPILED and not run. Compiling them is half
# the point — a public repo whose test sources do not build is not a repo anyone can contribute to,
# and that is exactly what shipped here until the *ClosedIT rule cut it.
# `clean` is load-bearing: profile excludes stop RE-compiling pro sources but never delete the
# .class files a previous full-edition build left in target/ — without clean, the absence
# assertions below would read stale artifacts instead of this build.
mvn -B -ntp -q -DskipTests -DopenEdition clean package -pl lumnik-hub -am

echo "→ verifying the closed side is absent from the runtime classpath"
# Assert the directory first: `ls <missing> | grep … || true` would swallow the ls failure and
# report "no closed jars" about a directory that is not there — an absence check must never
# pass blind (same family as the unzip check below and check-bounded-contexts.sh's scan()).
libdir=lumnik-hub/target/quarkus-app/lib/main
if [ ! -d "$libdir" ]; then
  echo "❌ $libdir missing — the build layout changed and this check is blind"
  exit 1
fi
closed=$(ls "$libdir" | grep -Ei 'lumnik-rag|llm-langchain|langchain4j|kafka' || true)
if [ -n "$closed" ]; then
  echo "❌ closed-edition jars on the open classpath:"
  echo "$closed" | sed 's/^/    /'
  exit 1
fi
echo "✓ no lumnik-rag / llm-langchain / langchain4j / kafka jar"

echo "→ verifying the pro-only code is absent (io.lumnik.hub.pro: scim, jit, kafka / identity.scim)"
if [ -d lumnik-hub/target/classes/io/lumnik/hub/pro ]; then
  echo "❌ io/lumnik/hub/pro classes compiled into the open edition"
  exit 1
fi
# Resolve the jar first and fail loud if it is not there — a bad glob feeding unzip would make
# this check pass silently (fail open), the exact trap check-bounded-contexts.sh closed in #191.
identity_jar=$(ls lumnik-hub/target/quarkus-app/lib/main/*lumnik-identity*.jar 2>/dev/null | head -1)
if [ -z "$identity_jar" ]; then
  echo "❌ lumnik-identity jar not found in quarkus-app/lib/main — this check needs updating"
  exit 1
fi
# unzip runs SEPARATELY from grep: in `unzip | grep || true` a failing unzip is unrecoverable —
# even under pipefail the pipeline reports grep's rc=1 (rightmost non-zero), and || true eats it,
# turning "could not read the jar" into "no scim classes". Read first, fail loud, then filter.
identity_listing=$(unzip -l "$identity_jar") || {
  echo "❌ could not read $identity_jar (unzip exit $?) — refusing to report absence unseen"
  exit 1
}
scim_classes=$(printf '%s\n' "$identity_listing" | grep 'io/lumnik/identity/scim/' || true)
if [ -n "$scim_classes" ]; then
  echo "❌ io/lumnik/identity/scim classes in the open identity jar:"
  echo "$scim_classes" | sed 's/^/    /'
  exit 1
fi
echo "✓ no io/lumnik/hub/pro classes, no identity scim classes"

echo "→ starting a throwaway Postgres on :$PG_PORT"
docker rm -f "$PG_NAME" >/dev/null 2>&1 || true
docker run -d --name "$PG_NAME" \
  -e POSTGRES_USER=adempiere -e POSTGRES_PASSWORD=adempiere -e POSTGRES_DB=lumnik \
  -p "$PG_PORT":5432 \
  -v "$PWD/infra/postgres/init:/docker-entrypoint-initdb.d:ro" \
  pgvector/pgvector:0.8.6-pg16 >/dev/null
for _ in $(seq 1 60); do
  docker exec "$PG_NAME" pg_isready -U adempiere -d lumnik >/dev/null 2>&1 && break
  sleep 2
done
# No grants here: infra/postgres/init already does all of it (DB-level CREATE for lumnik_admin,
# the schemas, and USAGE for lumnik_app). Assert it ran, though — pg_isready answers as soon as the
# server accepts connections, and an init script that failed would surface far downstream as a
# permission error that reads like a seam break. Fail here, where the cause is still legible.
if ! docker exec "$PG_NAME" psql -U adempiere -d lumnik -tAc \
      "select 1 from pg_roles where rolname = 'lumnik_app'" | grep -q 1; then
  echo "❌ infra/postgres/init did not run: role lumnik_app is missing"
  exit 1
fi

echo "→ booting on :$HTTP_PORT"
# Boot from an EMPTY directory, with an absolute jar path, and hand the JVM every variable it
# needs rather than letting it find some of them lying around. Quarkus loads a `.env` from the
# working directory: a developer's gitignored .env carries LUMNIK_SECRET_MASTER_KEY, so this
# witness passed on a laptop and failed on every runner from the night it was added — the worst
# shape a guard can take, because "works for me" is guaranteed. From a scratch cwd there is
# nothing ambient to borrow, and local and CI finally see the same thing.
BOOT_CWD=$(mktemp -d -t open-edition-boot-cwd.XXXXXX)
JAR="$PWD/lumnik-hub/target/quarkus-app/quarkus-run.jar"
(
  cd "$BOOT_CWD"
  # Throwaway: MasterKeyValidator and FingerprintHmacKey both only ask for non-blank,
  # not-the-placeholder, >= 16 chars. Neither key protects anything here — the container and
  # both keys die with the script.
  exec env \
    DB_URL="jdbc:postgresql://localhost:$PG_PORT/lumnik" \
    QUARKUS_HTTP_PORT="$HTTP_PORT" \
    LUMNIK_SECURITY_DEV_BYPASS=true \
    LUMNIK_SECRET_MASTER_KEY="open-edition-witness-throwaway-key" \
    LUMNIK_ENTITY_RESOLUTION_HMAC_SECRET="open-edition-witness-throwaway-hmac-secret" \
    "$JAVA" -jar "$JAR"
) > "$LOG" 2>&1 &
APP_PID=$!

for _ in $(seq 1 90); do
  grep -qE 'started in|Failed to start' "$LOG" 2>/dev/null && break
  sleep 2
done

if ! grep -q 'started in' "$LOG"; then
  echo "❌ the open edition did NOT start:"
  grep -vE '^\s+at ' "$LOG" | tail -25 | sed 's/^/    /'
  exit 1
fi

code=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$HTTP_PORT/q/health/live" || echo 000)
if [ "$code" != "200" ]; then
  echo "❌ started but /q/health/live answered HTTP $code"
  exit 1
fi

grep -m1 'started in' "$LOG" | sed 's/^/    /'
echo "✓ the open edition boots and serves (health 200)"
echo ""
echo "Open-edition boot witness PASSED."
echo "NOTE: this run left OPEN-edition classes in the modules' target/ trees. A later"
echo "      @QuarkusTest run without -am resolves those via the Quarkus workspace and fails"
echo "      on absent pro/scim beans — rebuild the full edition (mvn ... -am) first."
