# The métier PWA — your data in your pocket

The hub rendered on a phone. `/app/` is a small Preact app served by the hub itself —
no app store, no build step, no new endpoints: it speaks the same scope-bound API that
`lm` does, and installs from the browser like any PWA. One deployment carries every
métier; the `?m=` parameter decides whose app it is.

The UI speaks French — it is built for the métier user, not the integrator.

## Reaching it

```
https://<your-host>/app/            # the clients métier (default)
https://<your-host>/app/?m=stock    # the stock métier
```

Each métier in the registry carries its own title, home-screen icon, and web manifest
(`manifest-clients.json`, `manifest-stock.json`), swapped in before first paint — so
"Clients" and "Stock" install as two distinct apps on the same phone. An unknown or
absent `?m=` falls back to `clients`; it never errors.

What a métier user actually *sees* inside is decided elsewhere: by the saved views the
integrator has [applied](views.md) for that scope, and by the `scope:<tag>` role on the
user's account.

## Installing it on a phone

!!! note "At fleet scale: your MDM, not AirDrop"
    The gestures below are per-device — right for a pilot. For a managed fleet, push the
    same two artifacts through your MDM: the TLS façade's root CA as a trusted
    certificate, and the PWA URL as a managed web app / home-screen shortcut. The
    per-device steps then disappear entirely.

The PWA needs a **secure origin**: service workers and the real install prompt only
exist over `https`. The self-host stack ships a TLS façade for exactly this —
one Caddy origin fronting hub and Keycloak, with automatic Let's Encrypt for a public
`LUMNIK_TLS_HOST` (see `deploy/tls/README.md`). On a LAN address Caddy uses its
internal CA; trust its root on the device or installability is degraded (the
browser's rule, not ours).

Then, on the phone:

1. Open `https://<host>/app/?m=<métier>` and sign in.
2. **iOS Safari**: Share → *Add to Home Screen*. **Android Chrome**: the install
   prompt, or ⋮ → *Add to Home screen*.
3. The icon and name are the métier's own; the app opens standalone, no browser chrome.

## The three tabs

Same three surfaces on every device — bottom tabs on a phone, a left rail on a display.

### Fiches — the saved views

The tab discovers its views from the hub (`GET /api/views?scope=<métier>`), then fetches the
chosen manifest by name (`GET /api/views/{name}`):

| Views applied for your métier | What you see |
|---|---|
| One | It opens directly. |
| Several | A picker to choose from. |
| **None** | An amber message naming the cause — ask your integrator to apply one (`POST /api/views/apply`, or `lm view apply -f`). |

Rendering and filtering go through the same `POST /api/views/render` as `lm view`, with the
same [refusal rules](views.md#refusal-rules): filters are sent raw, the hub is the one
referee, and a refused filter keeps the last good result on screen with the refusal shown
under the filter bar.

Tapping a row opens the detail (`POST /api/views/detail`) — including the `_conflicts`
ledger: which source disagreed on which field, or an explicit "all sources agree".

### Décisions — the inbox

The [inbox](inbox.md) fold (`GET /api/inbox`), newest first, with the ack gesture —
✓ *Traiter* posts `POST /api/inbox/{id}/ack`, the same idempotent ack-is-an-event as
`lm inbox ack`. The unacked count rides as a badge on the tab from the first frame.

!!! note "Tenant-wide, for now"
    Decisions are not yet bounded by métier — the tab shows the whole tenant's
    decisions and says so in its footer. Per-métier bounding is a known follow-up.

### Chat — ask your data

The guarded analytic ask, through the `lm_user` door (`POST /api/rag/chat`, analytic
mode, bound to the métier's scope). An answer always shows the SQL it ran and the row
count, folded under the reply. An answer that ran **no** SQL is an abstention and wears
the amber refusal tone — the same [honesty contract](../transparency/ask-honesty.md)
as everywhere else. The thread survives tab switches but not a reload; nothing is
stored.

## Responsive modes

One codebase, three postures — CSS breakpoints, not separate apps:

| Width | Fiches | Navigation | Detail |
|---|---|---|---|
| phone (< 640px) | cards | bottom tabs | bottom sheet |
| tablet (≥ 640px) | table | bottom tabs | bottom sheet |
| display (≥ 900px) | table | left rail | pinned side panel |

## Identity & scope

The PWA is an OIDC **public client**: `lumnik-spa`, authorization code + PKCE (S256)
against the realm. Tokens live in `sessionStorage` for the tab's lifetime; a silent
refresh renews them ~60 s before expiry; logout is RP-initiated (Keycloak session
ends too, not just the local one). Under the TLS façade, Keycloak is reached
same-origin at `/realms/lumnik` — one issuer for every device.

!!! warning "External IdP: `lumnik-spa` needs an audience mapper"
    The hub requires `aud=lumnik-backend` on every token. The bundled realm carries
    the mapper for `lumnik-spa`; if you broker or replace Keycloak with your own IdP,
    the SPA client must add the same audience mapper — without it, every PKCE token
    is a plain `401` at the hub, with nothing wrong visible on the login side.
    (Learned live: see `docs/studies/2026-07-11-mobile-metier-renderer-study.md`.)

Access is the métier-scope boundary, nothing PWA-specific: the user needs the
`scope:<tag>` role for their métier. Views, entities, and chat all refuse uniformly —
`403 {"error":"scope not granted"}` — and the PWA renders that as the amber
refusal, never a broken screen.

## Offline behavior

The service worker caches the **app shell only** — HTML, CSS, modules, icons. Data is
**never** cached: everything under `/api/*` and `/realms/*` bypasses the worker
entirely and always hits the network. Tenant data and tokens never touch disk.

So offline, the app opens instantly — and says the truth: an "Hors ligne" screen with
a retry button, instead of stale rows pretending to be fresh. When the network returns,
so does the data.

!!! note "Deploying a shell change"
    The browser only re-installs the shell when `sw.js` itself changes bytes. Every
    shell-file change must bump the `CACHE` version in `app/sw.js` — otherwise
    returning visitors keep the old shell forever.

## How old is what I'm looking at?

lumnik is a scheduled mirror, not a live feed: a change in the source appears at the
**next run that reads it** (its cron, or a manual `lm connector run`) — deletes never
propagate, and a jdbc watermark run only re-reads rows whose watermark advanced. Rows in
`connector.t_*` tables carry an `_ingested_at` timestamp (REST hybrid tables carry
`indexed_at`), but treat it with care: under the default conflict mode an unchanged
re-ingested row keeps its *first* timestamp. The reliable signal is the **run history**,
and chat answers now carry it: every answered ask includes a `data as of` timestamp —
the scope's last completed run (`lm ask` prints it; the API field is `dataAsOf`).
The PWA shows the same dial: the chat answer and the Fiches list both carry a quiet
« données : il y a … » line — absent when the scope has no completed run, never a
fabricated age. For the schedule itself, your integrator reads the run history in
one command (`lm run list`, or the TUI's `:runs`).

## How to…

### Add a métier

A métier is an entry in the app's registry, not a deployment. Today the registry is
part of the hub's baked-in app sources
(`lumnik-hub/src/main/resources/META-INF/resources/app/`), so adding one is a source
change plus a hub rebuild:

1. **Manifest + icons** — `manifest-<tag>.json` (name, `start_url: /app/?m=<tag>`)
   and its `icon-<tag>-192/512.png`.
2. **Registry entry** — add `<tag>` to `METIERS` in `src/config.mjs` (scope, title,
   manifest, icon).
3. **Shell list** — add the new files to `SHELL` in `sw.js` and **bump `CACHE`**.
4. **Server side** — apply at least one saved view for the scope
   (`POST /api/views/apply`) and grant the users their `scope:<tag>` role in Keycloak.

Without step 4 the app still opens — Fiches states that no view is applied, and
every scoped call refuses with the uniform 403.

## See also

- [kind: View](views.md) — the manifests the Fiches tab renders.
- [Inbox](inbox.md) — the decisions fold behind the Décisions tab.
- [Scopes](../rag/scopes.md) — the boundary the whole app lives within.
