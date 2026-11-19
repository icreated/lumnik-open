# Sourced by up.sh (#1997). Keycloak kept its realms in an H2 file, in the volume <project>_selfhost_kc, until it moved
# to the stack's PostgreSQL. On such an install, this moves the lumnik realm with its users, their passwords and
# attributes to the database before the new Keycloak starts. The volume stays: it is the way back.
#
# Once moved, the volume records the H2 file's checksum (moved-to-postgresql). A file that changed since — an older
# snapshot restored into the volume — or a move that never finished is moved again: Keycloak's import replaces the
# realm in PostgreSQL, and what was changed there since is lost. Needs COMPOSE and compose_project (volumes.sh).

# The Keycloak that wrote those H2 files: the one the self-host compose ran before #1997.
KC_H2_IMAGE=quay.io/keycloak/keycloak:26.0
KC_H2_EXPORT=export-1997
KC_H2_FILE=/d/h2/keycloakdb.mv.db

# h2_shell <volume> <script> [args…] — a shell in the old image, the volume at /d, no network.
h2_shell() {
  local vol="$1" script="$2"
  shift 2
  docker run --rm --network none --security-opt no-new-privileges:true --cap-drop ALL \
    -v "$vol:/d" --entrypoint /bin/sh "$KC_H2_IMAGE" -c "$script" _ "$@"
}

move_keycloak_off_h2() {
  local vol rc=0 out
  vol="$(compose_project)_selfhost_kc"
  docker volume inspect "$vol" >/dev/null 2>&1 || return 0
  # 0: to move; 3: no H2 file; 4: moved already.
  h2_shell "$vol" '[ -f "$1" ] || exit 3; [ "$(sha256sum < "$1")" = "$(cat /d/moved-to-postgresql 2>/dev/null)" ] && exit 4
    exit 0' "$KC_H2_FILE" || rc=$?
  case "$rc" in
    0) ;;
    3|4) return 0 ;;
    *) echo "!! could not read $vol: Keycloak was not started, nothing changed." >&2; exit 1 ;;
  esac
  echo "==> moving Keycloak's realm from its H2 file to PostgreSQL (the volume $vol is kept)"
  "${COMPOSE[@]}" stop keycloak >/dev/null 2>&1 || true   # H2 holds a lock while it runs
  # On failure, Keycloak's last lines say why.
  if ! out="$(docker run --rm --network none --security-opt no-new-privileges:true --cap-drop ALL \
      -v "$vol:/opt/keycloak/data" "$KC_H2_IMAGE" \
      export --dir "/opt/keycloak/data/$KC_H2_EXPORT" --realm lumnik --users realm_file 2>&1)"; then
    {
      echo "$out" | tail -n 5
      echo "!! could not export the lumnik realm from $vol: Keycloak was not started, nothing else changed."
      echo "   To start over with an empty realm instead, rename or remove that volume, then re-run this script."
    } >&2
    exit 1
  fi
  if ! out="$("${COMPOSE[@]}" run --rm --no-deps -v "$vol:/h2:ro" keycloak \
      import --dir "/h2/$KC_H2_EXPORT" --optimized 2>&1)"; then
    {
      echo "$out" | tail -n 5
      echo "!! could not import the exported realm into PostgreSQL: Keycloak was not started. The export is in $vol, at $KC_H2_EXPORT/."
    } >&2
    exit 1
  fi
  # The checksum of the file as the export left it: opening an H2 file rewrites it. The export goes: it holds the
  # password hashes in plain JSON, and PostgreSQL has them now.
  h2_shell "$vol" 'sha256sum < "$1" > /d/moved-to-postgresql && rm -rf "/d/$2"' "$KC_H2_FILE" "$KC_H2_EXPORT" \
    || echo "  !! could not mark $vol as moved: the next run moves the realm again" >&2
  echo "    realm lumnik moved, with its users and their passwords; every session ends: sign in again (lm login)"
}
