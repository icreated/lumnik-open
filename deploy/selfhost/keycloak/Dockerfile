# The self-host stack's Keycloak (#1997): built once for the stack's PostgreSQL, so it starts in production mode with
# `start --optimized` — no rebuild at start, and a read-only root file system. A new version goes here, in
# the image tag of docker-compose.selfhost.yml, and in keycloak.image of deploy/helm/lumnik/values.yaml.
FROM quay.io/keycloak/keycloak:26.8.2
RUN /opt/keycloak/bin/kc.sh build --db=postgres --cache=local
