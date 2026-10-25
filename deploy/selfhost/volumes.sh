# Sourced by up.sh (#1579). A volume freezes the passwords of the .env that created it — Postgres' superuser,
# Keycloak's admin. A fresh .env over surviving volumes brings the hub up and leaves the realm accounts locked,
# behind a hint about .env when the cause is the volumes. Refuse before writing anything; never remove them.
# Spec: scripts/check-selfhost-fresh-env.sh.

refuse_fresh_env_over_volumes() {
  # Compose's default project name: the folder's, lower-cased, kept to [a-z0-9_-].
  local project="${COMPOSE_PROJECT_NAME:-$(basename "$ROOT" | tr '[:upper:]' '[:lower:]' | sed 's/[^a-z0-9_-]//g')}"
  local all found
  # Fail closed: a volume list we could not read is not an empty one, and the .env written next would stay.
  if ! all="$(docker volume ls -q --filter "label=com.docker.compose.project=$project")"; then
    echo "!! could not list Docker volumes — is Docker running? Nothing was written; re-run once it is." >&2
    exit 1
  fi
  found="$(echo "$all" | grep -E '_selfhost_(pg|kc)$' || true)"
  [[ -z "$found" ]] && return 0
  {
    echo "!! no .env, but this stack's volumes survive from an earlier install:"
    echo "$found" | sed 's/^/     /'
    echo "   They keep the passwords of the .env that created them; fresh secrets would lock Keycloak and the realm accounts."
    echo "   Either put back the .env that created them (your backup), then re-run this script,"
    echo "   or remove them — this DELETES the hub's data and Keycloak's:"
    echo "     docker compose -p $project -f docker-compose.selfhost.yml down -v"
  } >&2
  exit 1
}
