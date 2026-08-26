# Hooks — contributor guide

Installer/integrator-facing docs for the *mapping* hook family (data repairs at ingestion
time — 33 built-ins plus a Kotlin escape hatch) live at `docs/connectors/transformers.md`.
This file documents the **platform hook SPI** — lifecycle hooks, action hooks and callouts —
for whoever is writing a customization JAR against `lumnik-hub`. It is developer content and
does not appear on the docs site.

## What actually fires today

Three families share this package, and they are not equally wired up. Read this before
writing a hook — the previous version of this guide implied all three fire automatically on
product events, and two of the three do not:

- **Lifecycle hooks** (`LifecycleHook<T>`, `HookContext`, `target:` + `events:` in the
  manifest) are a pure **SPI**. `HookSpi.fire(LifecycleEventSpi, Object)`
  (`lumnik-common/src/main/java/io/lumnik/common/hook/HookSpi.java:4`) is implemented by
  `HookExecutor` (`HookExecutor.java:24,61-63`), but **nothing in product code calls it** —
  and, more precisely, nothing anywhere in the repo does either. The one `.fire(` call outside
  the `hook` package itself, `HookTestKit.fire(...)`
  (`lumnik-hub/src/test/java/io/lumnik/hub/hook/testkit/HookTestKit.java:22`), invokes
  `HookExecutor.fire(LifecycleEvent, T)` — the platform-typed overload — not the
  `HookSpi.fire(LifecycleEventSpi, Object)` interface method, so `HookSpi.fire` as declared has
  **zero callers in this codebase, test or product**. There is no built-in domain entity to fire on
  either — `io.lumnik.hub` is the only package under `io.lumnik` in this module. In practice:
  lifecycle hooks fire only when **your own JAR** injects `HookSpi` (or, from inside
  `lumnik-hub`, calls `HookExecutor.fire(...)` directly) at the right point in your own
  create/update/workflow code.
- **Action hooks** (`ActionHandler`, `@ActionHook("module.action")`) *are* reachable:
  `ActionHookInterceptor` is a globally-active CDI interceptor, registered in
  `META-INF/beans.xml` (`<interceptors><class>io.lumnik.hub.hook.ActionHookInterceptor</class></interceptors>`)
  so Quarkus Arc activates it — its own Javadoc says as much (`ActionHookInterceptor.java:14`);
  `@Priority` (`ActionHookInterceptor.java:18`) only orders it relative to other interceptors,
  it does not activate it — and it fires `HookExecutor.fireAction(...)` BEFORE and AFTER any
  method it wraps. But **no product
  method carries `@ActionHook` today**; the only occurrence of the annotation in the whole
  tree is the interceptor's own self-binding. The mechanism is live; nothing in the shipped
  product uses it yet. Annotate a method in your own JAR and it starts firing.
- **Callouts** (`Callout<T>`, `CalloutResult`) are reachable over HTTP right now:
  `CalloutResource` (`CalloutResource.java:15-19`) serves
  `POST /api/callouts/{moduleId}/{entityType}/{field}` behind `lm_user`, and resolves the
  entity class by rebuilding `"io.lumnik." + moduleId + "." + entityType` from those two URL
  segments alone (`CalloutResource.java:64-66`) — it never reads the manifest's `entity:` string
  directly. So a callout is only reachable over HTTP if `entity:` in `hooks.yaml` is written as
  exactly `io.lumnik.<moduleId>.<entityType>`; anything else (a `com.acme.*` class, say) can
  never be found by that lookup, permanently. Register one that follows the convention and it
  is callable immediately — no product frontend calls it today, but the endpoint doesn't need
  one to work.

None of this is a defect — it's infrastructure ahead of its first tenant. If you are writing
a customization JAR, action hooks and callouts need only your `hooks.yaml` + handler class;
lifecycle hooks additionally need your own code to call `HookSpi.fire(...)`.

## hooks.yaml structure

```yaml
module: my-customization      # lowercase with hyphens; must be unique
version: 1.0.0
description: "Optional description"

hooks:
  - id: my-lifecycle-hook
    target: com.acme.crm.BusinessPartner   # your own class, not a lumnik one
    events: [BEFORE_CREATE, BEFORE_UPDATE]
    handler:
      type: java
      class: com.acme.hooks.MyLifecycleHook
    priority: 100             # lower = earlier; default 100
    tenants: ['*']            # '*' = all tenants; or [1, 2, 3]
    enabled: true

  - id: my-action-hook
    action: partner.credit-review   # format: module.action (hyphen allowed)
    when: BEFORE                    # BEFORE or AFTER
    handler:
      type: java
      class: com.acme.hooks.MyActionHook

  - id: my-kotlin-hook
    target: com.acme.crm.BusinessPartner
    events: [AFTER_COMPLETE]
    handler:
      type: kotlin
      script: scripts/com/acme/MyScript.kts

callouts:
  - id: my-callout
    entity: io.lumnik.acmecrm.BusinessPartner   # must be io.lumnik.<moduleId>.<entityType> —
                                                 # CalloutResource rebuilds this exact string
                                                 # from the URL, it never reads this field
    field: creditLimit
    handler:
      type: java
      class: com.acme.hooks.MyCallout
```

Schema: `lumnik-hub/src/main/resources/META-INF/schemas/hooks-schema.json`, enforced at
startup by `HookManifestScanner` for every `META-INF/hooks.yaml` on the classpath — an
invalid manifest fails boot.

### Validation rules

- `module`: `^[a-z][a-z0-9-]*$`
- `id`: `^[a-z][a-z0-9-]*$`
- `action`: `^[a-z][a-z0-9-]*\.[a-z][a-z0-9-]*$` (hyphens allowed, underscores are not)
- A hook must have either (`target` + `events`) or (`action` + `when`), not both — enforced by
  the JSON Schema `oneOf`.

## Hook types

### Lifecycle hook

```java
@ApplicationScoped
public class MyHook implements LifecycleHook<BusinessPartner> {

    @Override
    public void onBeforeCreate(BusinessPartner p, HookContext ctx) {
        if (someCondition(p)) {
            ctx.fail("my_error_code", "Human-readable message");
        }
    }

    @Override
    public void onAfterUpdate(BusinessPartner p, HookContext ctx) {
        ctx.log("Partner updated: " + p.getCode());
    }
}
```

All methods on `LifecycleHook<T>` are default no-ops — implement only what you need. Events:
`BEFORE_CREATE`, `AFTER_CREATE`, `BEFORE_UPDATE`, `AFTER_UPDATE`, `BEFORE_DELETE`,
`AFTER_DELETE`, `BEFORE_ARCHIVE`, `AFTER_ARCHIVE`, `BEFORE_PREPARE`, `AFTER_PREPARE`,
`BEFORE_COMPLETE`, `AFTER_COMPLETE`, `BEFORE_VOID`, `AFTER_VOID`, `BEFORE_CLOSE`,
`AFTER_CLOSE`, `BEFORE_REACTIVATE`, `AFTER_REACTIVATE` (`LifecycleEvent.java`). For this to
run, something must call `HookSpi.fire(event, entity)` — see "What actually fires today" above.

### Action hook

```java
@ApplicationScoped
public class MyActionHook implements ActionHandler {
    @Override
    public void handle(Object payload, HookContext ctx) {
        // String.valueOf, not payload.toString(): payload is null whenever the intercepted
        // method takes no arguments, and Map.of rejects null values as well.
        ctx.log("Action fired", Map.of("payload", String.valueOf(payload)));
    }
}
```

Fires around any method (in any deployed class, including your own) annotated
`@ActionHook("module.action")` — `ActionHookInterceptor` wraps it, calling
`fireAction(action, BEFORE, arg0)` before and `fireAction(action, AFTER, arg0)` after.

**`payload` may be null.** `ActionHookInterceptor.java:30` passes the intercepted method's
first argument, or `null` when it has none — so annotate a no-arg method and every handler
that dereferences `payload` blindly throws. Both phases receive the same `arg0`: a handler
cannot see what the method returned.

### Kotlin script hook

Scripts receive three bindings: `event` (`LifecycleEvent`, null for action hooks), `entity`,
`ctx` (`HookContext`). Loaded and compiled by `KotlinScriptHost`
(`KotlinScriptHost.java:39-77`), which resolves `script:` as a classpath resource — there is
no filesystem-path option, see **Trust boundary** below.

```kotlin
import com.acme.crm.BusinessPartner
import io.lumnik.hub.hook.HookContext

val ent = bindings["entity"] as BusinessPartner
val ctx = bindings["ctx"]    as HookContext

if (ent.name?.contains("VIP", ignoreCase = true) == true) {
    ctx.log("VIP partner: ${ent.code}")
}
```

### Callout

Field-level reactive logic, invoked over HTTP as the frontend edits a field.

```java
@ApplicationScoped
public class MyCallout implements Callout<BusinessPartner> {
    @Override
    public CalloutResult onChange(BusinessPartner p, HookContext ctx) {
        if (p.getCreditLimit() != null && p.getCreditLimit().compareTo(THRESHOLD) > 0)
            return CalloutResult.patch(Map.of("requiresApproval", true));
        return CalloutResult.patch(Map.of("requiresApproval", false));
    }
}
```

`POST /api/callouts/{moduleId}/{entityType}/{field}` (`lm_user`) resolves the entity class as
`io.lumnik.<moduleId>.<entityType>`, deserializes the request body into it, and calls the
registered `Callout`. `CalloutResult.patch(Map)` returns field patches; `CalloutResult.error(...)`
returns a `FieldError` instead (`CalloutResult.java`). A Kotlin-handler callout is rejected —
`CalloutResource` throws `501` for `HookHandler.KotlinHandler` (`CalloutResource.java:57-58`);
callouts are Java-only in v1.

## HookContext API

```java
ctx.tenantId()                           // current tenant
ctx.userId()                             // current user
ctx.user().hasRole("lm_admin")           // role check
ctx.fail("code", "message")              // cancel the operation (throws HookCancellation → BusinessException → 409)
ctx.fail("code", "message", details)     // cancel with extra detail map
ctx.event("my.event", payload)           // publish a domain event via OutboxPublisher (async post-commit)
ctx.log("message")                       // structured log
ctx.log("message", Map.of("k", "v"))     // structured log with extra fields
ctx.lookup(BusinessPartner.class, id)    // read-only entity lookup (Optional<E>)
ctx.hookId(); ctx.module(); ctx.timestamp()
```

(`HookContext.java`)

## Priority and failure behavior

`HookRegistry` sorts bindings for the same target/event (or action/phase) by `priority`
ascending — lower runs first, default 100 (`HookRegistry.register`, `HookExecutor.fire`/
`fireAction` iterate that sorted list). A `ctx.fail(...)` throws `HookCancellation`, which
`HookExecutor.invoke` catches and rethrows as `BusinessException`; that exception propagates
out of the `for` loop, so **subsequent hooks in the same call do not run**, and
`BusinessExceptionMapper` turns it into `HTTP 409` with `{code, message, details?}`
(`BusinessExceptionMapper.java`). A hook that throws anything else (a bug) surfaces as a plain
`500` — there is no swallow-and-continue.

Both paths **roll the transaction back**, not just abort the response: `HookCancellation` and
`BusinessException` are unchecked (`extends RuntimeException` — `HookCancellation.java:5`,
`BusinessException.java:6`), and any other hook failure is likewise an unchecked exception, so
either one propagates straight out of whatever `@Transactional` boundary your own create/update
code placed the `HookSpi.fire(...)` call inside — Quarkus rolls back on an unhandled
`RuntimeException` by default. A `ctx.fail(...)` veto is therefore a real veto: the create or
update it cancelled leaves no trace, not just an error response with the write already applied.

## Trust boundary (Kotlin scripts)

Kotlin hook scripts run with **full JVM trust** — no sandbox, no timeout, no memory cap
(nothing in `KotlinScriptHost` or `KotlinCellHook` enforces any). Platform hook scripts
(`KotlinScriptHost`) are always resolved via the context classloader — there is no filesystem
path option at all. The mapping-hook Kotlin escape hatch (`KotlinCellHook`, used from
connector manifests) is explicit about the same constraint: `script:` must be a `classpath:`
reference, and a `file:` path is rejected with `only classpath: scripts supported in v1`
(`KotlinCellHook.java:75-89`). Hot-reload is off by default
(`lumnik.hooks.kotlin.hot-reload=false`); scripts are compiled once and cached.

This is the canonical statement of the trust boundary. `docs/how-it-works.md` and
`docs/connectors/transformers.md` restate the fact in one sentence for the integrator
audience rather than linking here.

## Tenant scoping

- `tenants: ['*']` — fires for all tenants (default when `tenants` is absent) — `HookBinding.matchesTenant`.
- `tenants: [1, 42]` — fires only for those tenant IDs.
- A hook can be disabled per tenant regardless of its manifest `enabled:`/`tenants:` via the
  override store below — `HookExecutor` checks `HookOverrideStore.isEnabled(...)` before every
  invocation.

## Admin endpoints

```
GET    /api/platform/hooks                       → list all hooks and callouts        (HookResource)
GET    /api/platform/hooks/{id}                   → detail for a specific hook
POST   /api/platform/hooks/{hookId}/toggle        → enable/disable a hook for a tenant
GET    /api/platform/hooks/overrides?tenantId=N   → per-tenant script overrides        (HookOverrideResource)
GET    /api/platform/hooks/overrides/{hookId}?tenantId=N → one override
POST   /api/platform/hooks/overrides              → upsert {hookId, tenantId, script} (400 without tenantId/hookId)
DELETE /api/platform/hooks/overrides/{hookId}?tenantId=N → remove a tenant's script override
```

All seven sit under `/api/platform/*`, so **both** roles are required, not one:
`PlatformApiAuthFilter` (`PlatformApiAuthFilter.java:42-45`) gates the whole `/api/platform/*`
prefix on `lm_integrator` first — a caller without it gets `403 "missing role lm_integrator"`
before the request ever reaches the resource — and only then does `HookResource`/
`HookOverrideResource`'s own `@RequiresPermission("lm_admin")` apply. In dev-bypass mode
(`%dev`/`%test` profiles, `lumnik.security.dev-bypass=true`) all roles are granted
automatically, which is why this double gate is easy to miss in local testing.

## Connector mapping hooks (the other hook family)

Distinct from everything above: **mapping hooks** transform data *during ingestion*,
declared in a connector manifest's `spec.mapping` block. Full reference, including the
Kotlin escape hatch and the `aggregator`/csv-file interaction, is at
`docs/connectors/transformers.md` — that page is the source of truth for that family, not
this one.

## Testing

`HookTestKit` (`lumnik-hub/src/test/java/io/lumnik/hub/hook/testkit/HookTestKit.java`)
provides CDI accessors for the registry and executor:

```java
HookRegistry registry = HookTestKit.registry();
registry.allBindings().forEach(b -> System.out.println(b.id()));

HookTestKit.fire(LifecycleEvent.BEFORE_CREATE, myEntity);
```

For a full request-response test, use `@QuarkusTest` + RestAssured, passing tenant/user via
headers (the `/partners` path below is illustrative — stand in your own module's endpoint;
the hub itself does not ship one):

```java
given()
    .header("X-Tenant-ID", TENANT)
    .header("X-User-ID", 1L)
    .contentType("application/json")
    .body("{...}")
.when()
    .post("/api/tenants/" + TENANT + "/partners")
.then()
    .statusCode(409)
    .body("code", equalTo("my_error_code"));
```

See `HookManifestScannerIT`, `HookRegistryTest`, `HookOverrideStoreTest`,
`HookScriptOverrideIT`, `HookToggleIT` and `override/HookOverrideResourceIT` in the sibling
test tree for real, running examples of each piece above.
