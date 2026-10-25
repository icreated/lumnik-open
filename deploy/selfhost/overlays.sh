#!/usr/bin/env bash
# Sourced by deploy/selfhost/up.sh, run from the repo root once .env exists.
#
# Compose recreates a service from the files it is given NOW. A stack started with an overlay (the TLS façade,
# Search, the WOW demo mount, a debug file) used to lose it the moment up.sh named the base file alone: with the
# façade, the hub fell back to the localhost issuer and refused every token from the https origin. The running hub
# container records the files it was created with (compose label com.docker.compose.project.config_files): that
# record is the truth, and keep_running_overlays re-applies it.
#
# Reads SEARCH / SEARCH_CLUSTER (the caller's flags). Appends every recorded overlay to COMPOSE, in recorded order;
# a recorded Search provider sets SEARCH or SEARCH_CLUSTER and BUILD, unless the caller passed a provider flag.
# When the TLS façade is kept and neither the shell nor .env sets LUMNIK_TLS_HOST / LUMNIK_TLS_BIND, exports the
# values the running stack was started with: a forgotten LUMNIK_TLS_BIND=127.0.0.1 must not silently widen the
# façade to every interface. Exits 2 when a recorded file is gone or the façade's address cannot be found.
keep_running_overlays() {
  local hub recorded f name rel kept="" tls=0 rebuild=0 bind project caddy
  hub="$(docker compose -f docker-compose.selfhost.yml ps -aq hub)"
  [[ -n "$hub" ]] || return 0
  IFS=',' read -r -a recorded <<< "$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project.config_files"}}' "$hub")"
  for f in ${recorded[@]+"${recorded[@]}"}; do
    name="$(basename "$f")"
    rel="${f#"$PWD"/}"
    case "$name" in
      docker-compose.selfhost.yml) continue ;;
      docker-compose.search.yml|docker-compose.search-cluster.yml)
        if [[ "$SEARCH" -eq 1 || "$SEARCH_CLUSTER" -eq 1 ]]; then   # the caller chose the provider
          if [[ ( "$name" == docker-compose.search.yml && "$SEARCH_CLUSTER" -eq 1 ) \
             || ( "$name" == docker-compose.search-cluster.yml && "$SEARCH" -eq 1 ) ]]; then
            echo "!! this stack ran $rel; the provider flag switches it. Its containers keep running until ./deploy/selfhost/down.sh" >&2
          fi
          continue
        fi
        if [[ "$name" == docker-compose.search-cluster.yml && ! -f deploy/selfhost/search-cluster.sh ]]; then
          echo "!! this stack was started with $rel, but deploy/selfhost/search-cluster.sh is not in this edition." >&2
          exit 2
        fi
        BUILD=1 rebuild=1   # the published image never carries a provider
        if [[ "$name" == docker-compose.search.yml ]]; then SEARCH=1; else SEARCH_CLUSTER=1; fi ;;
      docker-compose.tls.yml) tls=1 ;;
    esac
    if [[ ! -f "$f" ]]; then
      echo "!! this stack was started with $rel, which no longer exists." >&2
      echo "   To start without it: ./deploy/selfhost/down.sh, then ./deploy/selfhost/up.sh (your data is kept)." >&2
      exit 2
    fi
    COMPOSE+=(-f "$rel")
    kept="$kept $rel"
  done
  [[ -n "$kept" ]] || return 0
  echo "==> keeping the overlays this stack was started with:$kept"
  echo "    (to drop one: ./deploy/selfhost/down.sh, then ./deploy/selfhost/up.sh — your data is kept)"
  [[ "$rebuild" -eq 0 ]] || echo "==> this stack runs Search: the hub is rebuilt from source, from the tree as it is checked out"
  [[ "$tls" -eq 1 ]] || return 0

  if [[ -z "${LUMNIK_TLS_HOST:-}" ]] && ! grep -q '^LUMNIK_TLS_HOST=.' .env; then
    # The façade's overlay sets the hub's issuer to https://<host>/realms/lumnik; read the host back from it.
    LUMNIK_TLS_HOST="$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$hub" \
      | sed -n 's#^QUARKUS_OIDC_TOKEN_ISSUER=https://\([^/]*\)/realms/lumnik$#\1#p')"
    if [[ -z "$LUMNIK_TLS_HOST" ]]; then
      echo "!! this stack runs the TLS façade, but its address was not found on the hub." >&2
      echo "   Run again with it: LUMNIK_TLS_HOST=<the façade's address> ./deploy/selfhost/up.sh" >&2
      exit 2
    fi
    export LUMNIK_TLS_HOST
  fi
  if [[ -z "${LUMNIK_TLS_BIND:-}" ]] && ! grep -q '^LUMNIK_TLS_BIND=.' .env; then
    project="$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' "$hub")"
    caddy="$(docker ps -aq --filter "label=com.docker.compose.project=$project" --filter label=com.docker.compose.service=caddy)"
    if [[ -n "$caddy" ]]; then
      bind="$(docker inspect --format '{{range (index .HostConfig.PortBindings "443/tcp")}}{{.HostIp}}{{end}}' "${caddy%%$'\n'*}")"
      if [[ -n "$bind" && "$bind" != 0.0.0.0 ]]; then export LUMNIK_TLS_BIND="$bind"; fi
    fi
  fi
}
