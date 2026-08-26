# Offboarding the integrator

> [SCIM](scim.md) covers the departure of a métier user. This page covers the other
> departure every RSSI documents: the **integrator/consultant** whose mission ends —
> what they could touch, and the last-day checklist.

## The access footprint

What an integrator typically held during the mission:

| Access | What it opens | Where it lives |
|---|---|---|
| A realm account with `lm_integrator` | the platform surface behind the integrator gate — connectors, runs, secrets *metadata*, discovery — plus `views/apply` and `workflows/apply`; many admin endpoints stack `lm_admin`/`lm_superadmin` on top ([roles](role-management.md)) | your IdP / the bundled realm |
| The `lm` CLI session | whatever the roles above open, from their workstation | tokens in `~/.lm/config.yaml` **on their machine** |
| The `.env` of the install | the master keys (`LUMNIK_SECRET_MASTER_KEY`, `LUMNIK_CRYPTO_SECRET_KEY`), Postgres and Keycloak admin passwords | the server they installed |
| Database credentials | `lumnik_admin` is **BYPASSRLS** — per-install generated; rotate by putting new values in `.env` and re-running `up.sh` ([credentials](rls.md#credentials)) | anyone who read the departing integrator's `.env` |
| SCIM tokens (if minted) | the `/scim/v2/*` provisioning surface | revocable, effect immediate |

## The clock, on the bundled realm

- Access tokens live **1 hour**; the refresh chain dies **at the latest 24 hours** after
  login (`accessTokenLifespan: 3600`, `ssoSessionMaxLifespan: 86400`; Keycloak's default
  idle timeout kills an *unused* chain in ~30 minutes — your own IdP may differ).
- Archiving the account denies **the very next request, even with a still-valid token** —
  both token shapes ([SCIM → deactivate](scim.md)). You do not wait for expiry.

## The last-day checklist

1. **Archive or disable the account in the IdP** (or `active: false` via SCIM). Effect:
   next request denied. On the **bundled realm**: Keycloak admin console at
   `http://localhost:8180` (admin credentials = `KEYCLOAK_ADMIN_PASSWORD` in the
   install's `.env`), realm `lumnik` → Users → the account → **Enabled: off** — Keycloak
   has no "archive" button; disabling is the gesture. Removing just the `lm_integrator`
   role also works but leaves a dormant account.
2. **Revoke any SCIM tokens** they minted: `DELETE /api/tenants/{tid}/scim-tokens/{id}` —
   immediate ([token lifecycle](scim.md#token-lifecycle)). Needs an `lm_admin` of the
   same tenant — check *before* the last day that the departing consultant was not the
   only one.
3. **Rotate what can be rotated** — as a planned maintenance window, not a 5 p.m.
   gesture: rotating the Postgres role passwords at the database **breaks the running
   hub** until the datasource env is hand-edited and the stack restarted (the passwords
   are not yet externally configurable — [the defaults caveat](rls.md#credentials)); the
   Keycloak admin password rotates freely. The **master keys cannot be rotated in
   place** ([the warning](rls.md#secrets-at-rest-the-master-key)); the nuclear option —
   a new key plus re-entering every stored secret by hand — exists, and for a
   contentious departure may be the decision to write down.
4. **Their workstation is best-effort**: you cannot remotely wipe `~/.lm/config.yaml`,
   but steps 1–2 bound it — on the bundled realm the tokens it holds die within the hour,
   the refresh chain within the day, and every request meanwhile is denied at the filter.
5. **Host access is your infrastructure's offboarding, not lumnik's** — SSH keys in
   `authorized_keys`, VPN, sudo on the server they installed. This page cannot revoke
   it; your IT offboarding must. Named here so nobody stops at step 4 believing the job
   done.

## Known gaps

There is no product surface listing "everything user X can reach" or their active
sessions — the checklist above **is** the procedure. The checklist also only revokes
what they could *reach*: what they **left behind** — connectors, mappings, and above all
`.kts` hooks, which are code the hub keeps executing after they leave — stays running.
Reviewing it is your change management; know that it is there. And as long as the
Postgres role passwords ship at defaults, rotating them (with the maintenance-window
caveat of step 3) remains the step that most deserves to actually happen.
