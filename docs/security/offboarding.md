# Offboarding the integrator

> [SCIM](scim.md) covers a business user's departure. This page covers the other one every
> security officer documents: the integrator whose mission ends. What they could touch, and the
> last-day checklist.

## The access footprint

| They held | It opens | It lives |
|---|---|---|
| A realm account with `lm_integrator` | connectors, runs, secrets *metadata*, views, workflows; usually `lm_admin` on top (entities, processes, secrets, discovery) — [the roles](role-management.md#which-role-for-which-gesture) | your IdP or the bundled realm |
| The `lm` CLI session | whatever those roles open, from their workstation | `~/.lm/config.yaml` **on their machine** |
| The install's `.env` | both master keys, the entity-resolution HMAC secret, the Postgres and Keycloak admin passwords, the bundled accounts' passwords, the three database role passwords | the server they installed |
| Database credentials | `lumnik_admin` bypasses RLS | anyone who read that `.env` |
| SCIM tokens, if minted | the `/scim/v2/*` provisioning surface | revocable, effect immediate |

## The clock {#the-clock}

Two gestures, two clocks. By default the hub checks a token against the IdP's keys only, not
whether the account still exists.

| Gesture | Stops | A live access token |
|---|---|---|
| **Archive the hub row**: `DELETE /api/tenants/{tid}/users/{id}` as `lm_admin`, or SCIM `active: false` | **the very next request**, with or without a `user_id` claim | refused at once |
| **Disable the account in the IdP** (Keycloak *Enabled: off*) | new logins and token refreshes | stays valid until it expires: **1 hour** on the bundled realm; the refresh chain dies 24 hours after login at the latest, about 30 minutes idle |
| **Disable the account in the IdP, with IdP verification on** | every request: the hub asks the IdP's UserInfo endpoint and fails authentication when it refuses | refused on the IdP's answer |

Do both. Your own IdP's lifespans may differ.

IdP verification is off by default and costs one IdP round trip per request. On Helm, set
`auth.verifyWithIdp=true`. The self-host compose file does not pass its variable,
`LUMNIK_AUTH_VERIFY_WITH_IDP`, to the hub.

## The last-day checklist

`lm` has no command for users or SCIM tokens: steps 1 and 2 are HTTP calls as an `lm_admin` of
the tenant. Check *before* the last day that the departing consultant is not the only one.

1. **Archive the hub row, then disable the IdP account.**
    - Archive: `DELETE /api/tenants/{tid}/users/{id}` (or SCIM `active: false`). Their next
      `lm` call prints `Error: access refused (403): user is deactivated`.
    - Disable: console at `http://localhost:8180`, realm `lumnik` → Users → the account →
      **Enabled: off**. No new token is issued. On Helm's bundled Keycloak this does not survive
      a pod restart: there, archiving the hub row is the step that lasts.
2. **Revoke any SCIM token they minted**: `DELETE /api/tenants/{tid}/scim-tokens/{id}`,
   immediate. [Token lifecycle](scim.md#token-lifecycle)
3. **Change every password they read in `.env`**, as a planned window, not a 5 p.m. gesture.
   The stack restarts.
    - **The bundled accounts** — `HUBADMIN_PASSWORD` (`hubadmin`, superadmin) and
      `INTEGRATOR_PASSWORD` — **and the three database role passwords**: put new values in `.env`,
      then re-run `up.sh` with the flags you installed with. It sets them all again:

        ```
          hubadmin: per-install password set (HUBADMIN_PASSWORD in .env)
          integrator: per-install password set (INTEGRATOR_PASSWORD in .env)
        ```

      On a TLS install, run the [overlay command](../deploy.md#one-https-origin-the-tls-proxy)
      again afterwards: `up.sh` leaves it out.
    - **The Postgres superuser**: `up.sh` does not change it. Set it in the database, then the
      same value as `POSTGRES_PASSWORD` in `.env`:

        ```bash
        docker compose -f docker-compose.selfhost.yml exec postgres \
          psql -U lumnik -d lumnik -c "ALTER ROLE lumnik PASSWORD 'the-new-password'"
        ```

    - **The Keycloak admin password**: change it in the console (realm `master` → Users →
      `admin` → Credentials), **then** the same value as `KEYCLOAK_ADMIN_PASSWORD` in `.env`.
      With only one of the two, the next `up.sh` prints `could not obtain a Keycloak admin token`.
    - **The master keys cannot be changed in place.** The only path is a new key plus re-entering
      every stored secret by hand. [The warning](rls.md#secrets-at-rest-the-master-key)
4. **Their workstation.** You cannot wipe `~/.lm/config.yaml` remotely; step 1 makes its token
   useless.
5. **Host access** is your infrastructure's offboarding: SSH keys, VPN, sudo on the server they
   installed.

## Known gaps

- No product surface lists "everything user X can reach" or their active sessions.
- What they **left behind** stays running: connectors, mappings and `.kts` hooks, which are code
  the hub keeps executing.
- A copy of `.env` plus any database dump decrypts every stored secret: the master keys cannot
  be changed in place (step 3).
