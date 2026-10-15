# syntax=docker/dockerfile:1

# ── build: compile the multi-module reactor (hub + its module deps) ──
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
# Extra Maven profiles, e.g. -Psearch-cluster. Empty in every published image: a Search provider is
# an explicit build choice, never something a standard image carries.
ARG MVN_PROFILES=""
RUN mvn -q -pl lumnik-hub -am package -DskipTests ${MVN_PROFILES}

# ── run: Quarkus fast-jar on a slim JRE, as a non-root user ──
FROM eclipse-temurin:21-jre
WORKDIR /app
# curl: for the container HEALTHCHECK in docker-compose.selfhost.yml (the JRE base ships none).
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
# Run as an unprivileged named user (group 0 for OpenShift-style arbitrary-uid
# compatibility). The hub is read-only at runtime (logs to stdout), so root is
# unnecessary blast radius.
RUN useradd -r -u 1001 -g 0 -d /home/lumnik -m -s /usr/sbin/nologin lumnik
COPY --from=build --chown=1001:0 /src/lumnik-hub/target/quarkus-app/ ./
USER lumnik
EXPOSE 8080
ENV QUARKUS_PROFILE=prod
ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]
