# Offboarding the integrator

> [SCIM](scim.md) covers a métier user's departure. This page covers the other one every
> security officer documents: the integrator whose mission ends. What they could touch, and the
> last-day checklist.

## The access footprint

| They held | It opens | It lives |
|---|---|---|
| A realm account with `lm_integrator` | connectors, runs, secrets *metadata*, discovery, `views/apply`, `workflows/apply`; often `lm_admin` on top | your IdP or the bundled realm |
| The `lm` CLI session | whatever those roles open, from their workstation | `~/.lm/config.yaml` **on their machine** |
| The install's `.env` | both master keys, the Postgres and Keycloak admin passwords, the three database role passwords | the server they installed |
| Database credentials | `lumnik_admin` bypasses RLS | anyone who read that `.env` |
| SCIM tokens, if minted | the `/scim/v2/*` provisioning surface | revocable, effect immediate |

## The clock {#the-clock}

Two different gestures, two different clocks. The hub validates tokens locally against the
IdP's keys and does not ask the IdP whether the account still exists.

| Gesture | Stops | A live access token |
|---|---|---|
| **Archive the hub row**: `DELETE /api/tenants/{tid}/users/{id}` as `lm_admin`, or SCIM `active: false` | **the very next request**: the filter checks the archived state on every call, for both token shapes | refused at once |
| **Disable the account in the IdP** (Keycloak *Enabled: off*) | new logins and token refreshes | stays valid until it expires: **1 hour** on the bundled realm; the refresh chain dies 24 hours after login at the latest, about 30 minutes idle |
| **Disable the account in the IdP, with `LUMNIK_AUTH_VERIFY_WITH_IDP=true`** | the hub then asks the IdP's UserInfo endpoint on every request and fails authentication when the IdP refuses; on the bundled realm UserInfo refuses a disabled account at once | refused on the IdP's answer |

Do both. Your own IdP's lifespans may differ. The knob costs one IdP round trip per request and
is off by default; on Helm it is `auth.verifyWithIdp`. Rehearsed end to end in the `oidc-external`
kind smoke test (`deploy/helm/up.sh`): with the knob on, a live, still-valid token is refused the
moment its account is disabled in the IdP; with the knob off, the same disabled account keeps
answering until the token expires, as the row above says.

## The last-day checklist

1. **Archive the hub row, then disable the IdP account.** Archive:
   `DELETE /api/tenants/{tid}/users/{id}` as an `lm_admin` of the tenant (or `active: false`
   via SCIM). Effect: the next request is refused, whatever the token. Then, on the bundled
   realm: console at `http://localhost:8180`, realm `lumnik` → Users → the account →
   **Enabled: off**, so no new token is ever issued. Keycloak has no "archive" button;
   disabling is its gesture.
2. **Revoke any SCIM token they minted**: `DELETE /api/tenants/{tid}/scim-tokens/{id}`,
   immediate. Needs an `lm_admin` of the same tenant: check *before* the last day that the
   departing consultant was not the only one. [Token lifecycle](scim.md#token-lifecycle)
3. **Rotate what can be rotated**, as a planned window, not a 5 p.m. gesture:
    - the three database role passwords: new values in `.env`, re-run `up.sh`; the stack
      restarts. [Credentials](rls.md#credentials)
    - the Keycloak admin password: rotates freely.
    - the master keys: **cannot be rotated in place**. The nuclear option, a new key plus
      re-entering every stored secret by hand, exists; for a contentious departure it may be
      the decision to write down. [The warning](rls.md#secrets-at-rest-the-master-key)
4. **Their workstation is best-effort.** You cannot wipe `~/.lm/config.yaml` remotely, but
   step 1 bounds it: every request is denied at the filter from the archive onward, and no
   refresh succeeds after the IdP disable.
5. **Host access is your infrastructure's offboarding, not lumnik's**: SSH keys, VPN, sudo on
   the server they installed. Named here so nobody stops at step 4 believing the job done.

## Known gaps

- No product surface lists "everything user X can reach" or their active sessions. The
  checklist above **is** the procedure.
- The checklist revokes what they could *reach*. What they **left behind** stays running:
  connectors, mappings, and above all `.kts` hooks, which are code the hub keeps executing.
  Reviewing it is your change management.
- The master keys cannot be rotated, so a departed integrator holding a copy of `.env` **and**
  any database dump decrypts every stored secret, indefinitely. That is why step 3's nuclear
  option exists.
