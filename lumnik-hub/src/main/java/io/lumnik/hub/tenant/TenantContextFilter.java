package io.lumnik.hub.tenant;

import io.lumnik.common.security.CurrentRolesHolder;
import io.lumnik.common.tenant.CurrentTenantHolder;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.json.JsonNumber;
import jakarta.json.JsonString;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import java.util.Optional;
import java.util.Set;

/**
 * Populates thread-local security context from every inbound request.
 * Dev mode (lumnik.security.dev-bypass=true): reads X-Tenant-ID / X-User-ID headers.
 * Staging/prod: extracts tenant_id + user_id claims from the Bearer JWT (Keycloak).
 */
@Priority(Priorities.AUTHORIZATION)
@Provider
@ApplicationScoped
public class TenantContextFilter implements ContainerRequestFilter, ContainerResponseFilter {

    /** The JSON key every refusal from this filter answers with. */
    private static final String ERROR = "error";

    private static final Logger LOG = Logger.getLogger(TenantContextFilter.class);

    public static final ThreadLocal<Long>      CURRENT_TENANT = new ThreadLocal<>();
    public static final ThreadLocal<Long>      CURRENT_USER   = new ThreadLocal<>();

    /**
     * Request property set by an AUTHENTICATION-priority filter (e.g. the pro SCIM one) that has
     * already authenticated the request and set the tenant itself. This filter then leaves the
     * context alone. A property, not a path match: the skip only ever happens for a request an
     * authenticator actually claimed, so no second matcher exists to drift into an auth hole.
     * Checked as {@code Boolean.TRUE} — presence alone does not skip.
     */
    public static final String PRE_AUTHENTICATED = "io.lumnik.auth.pre-authenticated";
    // Roles live in the cross-module CurrentRolesHolder (lumnik-common) — single source of
    // truth, readable from modules (lumnik-rag) that cannot see this filter at compile time.

    private static final String MDC_TENANT_ID = "tenantId";
    private static final String MDC_USER_ID = "userId";
    private static final String MDC_IMPERSONATED_TENANT = "impersonatedTenant";

    @ConfigProperty(name = "lumnik.security.dev-bypass", defaultValue = "false")
    boolean devBypass;

    @Inject
    Instance<JsonWebToken> jwtInstance;

    /** The Act-1 seam: the JIT implementation ships with the closed side; absent → 401 on
     *  user_id-less JWTs (fail closed). */
    @Inject
    Instance<ExternalIdentityResolver> externalIdentities;

    @ConfigProperty(name = "lumnik.oidc.subject-claim", defaultValue = "sub")
    String subjectClaim;

    @Inject
    UserLoginGuard loginGuard;

    @Override
    public void filter(ContainerRequestContext ctx) {
        clearJwtLocals();   // hygiene: never inherit a prior request's JWT context on this pooled thread

        // An earlier AUTHENTICATION-priority filter (SCIM in the pro edition) already
        // authenticated this request and set CurrentTenantHolder. Skip our JWT/header path AND
        // leave the RLS holder intact — clearing it here would null that tenant and break RLS
        // for the operation. Boolean.TRUE, not mere presence: anything else leaves us owning.
        if (Boolean.TRUE.equals(ctx.getProperty(PRE_AUTHENTICATED))) return;

        CurrentTenantHolder.clear();   // not pre-authenticated: this filter owns the tenant; start clean
        try {
            if (devBypass) {
                fromHeaders(ctx);
            } else {
                fromJwt(ctx);
            }

            CurrentTenantHolder.set(CURRENT_TENANT.get());

            if (CURRENT_TENANT.get() != null) MDC.put(MDC_TENANT_ID, CURRENT_TENANT.get());
            if (CURRENT_USER.get()   != null) MDC.put(MDC_USER_ID,   CURRENT_USER.get());
        } catch (RuntimeException e) {
            // If resolution throws (e.g. JIT hits a DB outage), the response filter may not run
            // on this path — clear the tenant/user context so it can't leak (RLS-critical) into
            // the next request executed on the same pooled thread.
            clearContext();
            throw e;
        }
    }

    /** Clear the JWT-path thread-locals + MDC (NOT the RLS holder — a pre-authenticating
     *  filter owns that on its path). */
    private static void clearJwtLocals() {
        CURRENT_TENANT.remove();
        CURRENT_USER.remove();
        CurrentRolesHolder.clear();
        MDC.remove(MDC_TENANT_ID);
        MDC.remove(MDC_USER_ID);
        MDC.remove(MDC_IMPERSONATED_TENANT);
    }

    /** Clear all per-request tenant/user/role state (thread-locals, RLS holder, MDC). */
    private static void clearContext() {
        clearJwtLocals();
        CurrentTenantHolder.clear();
    }

    @Override
    public void filter(ContainerRequestContext req, ContainerResponseContext res) {
        clearContext();
    }

    // ── Static accessors ────────────────────────────────────────────────────

    public static Long current()     { return CURRENT_TENANT.get(); }
    public static Long currentUser() { return CURRENT_USER.get(); }
    public static Set<String> currentRoles() {
        return CurrentRolesHolder.get();
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private void fromHeaders(ContainerRequestContext ctx) {
        String tenantHeader = ctx.getHeaderString("X-Tenant-ID");
        String userHeader   = ctx.getHeaderString("X-User-ID");

        if (tenantHeader != null) {
            try { CURRENT_TENANT.set(Long.parseLong(tenantHeader.strip())); }
            catch (NumberFormatException e) {
                LOG.warnf("Invalid X-Tenant-ID header: %s", tenantHeader);
            }
        }
        if (userHeader != null) {
            try { CURRENT_USER.set(Long.parseLong(userHeader.strip())); }
            catch (NumberFormatException e) {
                LOG.warnf("Invalid X-User-ID header: %s", userHeader);
            }
        }

        String imp = ctx.getHeaderString("X-Impersonate-Tenant");
        if (imp != null) {
            try {
                Long actingAs = Long.parseLong(imp.strip());
                MDC.put(MDC_IMPERSONATED_TENANT, actingAs);
                CURRENT_TENANT.set(actingAs);
            } catch (NumberFormatException e) {
                LOG.warnf("Invalid X-Impersonate-Tenant header: %s", imp);
            }
        }

        // Dev: grant all roles to simplify local development
        CurrentRolesHolder.set(Set.of("lm_admin", "lm_user", "lm_superadmin"));
    }

    /**
     * The four bindings a request carries, in the order they must happen: the tenant (RLS must see
     * it before any transaction runs), the roles, the user, then impersonation — which applies AFTER
     * the user is resolved so it never redirects JIT creation into the impersonated tenant.
     *
     * <p>Each step answers "may the request continue?". Every refusal below is pinned:
     * TenantContextFilterJwtTest covers seven of them, TenantImpersonationAuthIT the eighth.
     */
    private void fromJwt(ContainerRequestContext ctx) {
        if (jwtInstance.isUnsatisfied()) return;
        JsonWebToken token = jwtInstance.get();

        if (!bindTenant(ctx, token)) return;
        bindRoles(token);
        if (!bindUser(ctx, token)) return;
        applyImpersonation(ctx, token);
    }

    /** The tenant claim, set where RLS can see it BEFORE the JIT provisioner's transaction runs. */
    private boolean bindTenant(ContainerRequestContext ctx, JsonWebToken token) {
        Optional<Long> tenant = parseLongClaim(token, "tenant_id");
        if (tenant.isEmpty()) {
            abort(ctx, "missing or invalid tenant_id claim");
            return false;
        }
        CURRENT_TENANT.set(tenant.get());
        CurrentTenantHolder.set(tenant.get());
        return true;
    }

    private void bindRoles(JsonWebToken token) {
        Set<String> groups = token.getGroups();
        CurrentRolesHolder.set(groups != null ? groups : Set.of());
    }

    /** Two mutually exclusive paths: the realm emits user_id, or an external identity is resolved. */
    private boolean bindUser(ContainerRequestContext ctx, JsonWebToken token) {
        Optional<Long> userId = parseLongClaim(token, "user_id");
        return userId.isPresent()
            ? bindUserFromClaim(ctx, userId.get())
            : resolveExternalIdentity(ctx, token);
    }

    /**
     * The shipped realm emits user_id, so this path — not the resolver below — is what a still-valid
     * token of a SCIM-archived user rides in on. Mirror the resolver's guard: a row that exists but
     * is no longer login-active is denied on this very request. An id with no row passes through
     * unchanged (back-compat: the hub does not own every IdP's user table). Costs one PK SELECT per
     * request, as the resolver path already does. The guard bean is transactional — a bare read here
     * would lose the SET LOCAL tenant GUC and never see the row under enforced RLS
     * (UserDeactivationGuardRlsIT).
     */
    private boolean bindUserFromClaim(ContainerRequestContext ctx, Long userId) {
        if (loginGuard != null && loginGuard.isDeactivated(userId)) {
            LOG.warn("login denied: user is deactivated");
            LOG.debugf("  (user_id=%d)", userId);
            denyDeactivated(ctx);
            return false;
        }
        CURRENT_USER.set(userId);           // back-compat: our realm / any IdP that emits it
        return true;
    }

    private boolean resolveExternalIdentity(ContainerRequestContext ctx, JsonWebToken token) {
        String issuer = token.getIssuer();
        String subject = stringClaim(token, subjectClaim, null);
        if (subject == null || issuer == null || issuer.isBlank()) {
            abort(ctx, "missing subject/issuer claim");
            return false;
        }
        // The key (issuer, subject) maps to NOT-NULL varchar(255) columns and cannot be truncated
        // without changing identity — reject an oversized one (fail closed) rather than let it
        // become a 500 at persist. (Profile name/email are clamped in the provisioner.)
        if (subject.length() > 255 || issuer.length() > 255) {
            abort(ctx, "subject/issuer claim too long");
            return false;
        }
        if (externalIdentities.isUnsatisfied()) {
            // Open edition: no resolver ships. Refuse honestly rather than continue user-less.
            abort(ctx, "no external identity resolution in this edition (user_id claim required)");
            return false;
        }
        String name  = stringClaim(token, "name", subject);
        String email = stringClaim(token, "email", null);
        try {
            Long resolved = externalIdentities.get().findOrCreate(issuer, subject, name, email);
            if (resolved == null) {
                // A broken resolver must not yield a user-less authenticated request.
                abort(ctx, "external identity resolution returned no user");
                return false;
            }
            CURRENT_USER.set(resolved);
            return true;
        } catch (DeactivatedUserException e) {
            // Generic message — do NOT log the external subject/issuer (PII) at WARN; the 403
            // already communicates the denial to the caller. Details at DEBUG only.
            LOG.warn("login denied: external identity is deactivated");
            LOG.debug(e.getMessage());
            denyDeactivated(ctx);
            return false;
        }
    }

    /**
     * Impersonation (admin acting_as_tenant). ImpersonationResource already requires lm_superadmin
     * to START an impersonation, but this filter is what actually switches the RLS tenant on every
     * subsequent request — it must require the same role, or enforcement would rest entirely on the
     * IdP never minting the claim otherwise.
     */
    private void applyImpersonation(ContainerRequestContext ctx, JsonWebToken token) {
        Optional<Long> actingAsTenant = parseLongClaim(token, "acting_as_tenant");
        if (actingAsTenant.isEmpty()) return;
        if (!CurrentRolesHolder.get().contains("lm_superadmin")) {
            LOG.warn("acting_as_tenant claim rejected: bearer lacks lm_superadmin");
            ctx.abortWith(Response.status(Response.Status.FORBIDDEN)
                .entity(Map.of(ERROR, "lm_superadmin required to impersonate a tenant"))
                .type(MediaType.APPLICATION_JSON).build());
            return;
        }
        Long v = actingAsTenant.get();
        MDC.put(MDC_IMPERSONATED_TENANT, v);
        CURRENT_TENANT.set(v);
        CurrentTenantHolder.set(v);
    }

    /** The same 403 for both deactivation paths — the caller cannot tell which one denied it, by design. */
    private static void denyDeactivated(ContainerRequestContext ctx) {
        ctx.abortWith(Response.status(Response.Status.FORBIDDEN)
            .entity(Map.of(ERROR, "user is deactivated"))
            .type(MediaType.APPLICATION_JSON).build());
    }

    private static String stringClaim(JsonWebToken token, String claim, String fallback) {
        Object raw = token.getClaim(claim);
        if (raw == null) return fallback;
        // A custom claim (e.g. cohab_id) arrives as a JsonString — getString() avoids the
        // surrounding quotes that String.valueOf would keep.
        String s = (raw instanceof JsonString js) ? js.getString() : String.valueOf(raw);
        s = s.strip();
        return s.isBlank() ? fallback : s;
    }

    private void abort(ContainerRequestContext ctx, String msg) {
        LOG.warnf("JWT auth rejected: %s", msg);
        ctx.abortWith(Response.status(Response.Status.UNAUTHORIZED)
            .entity(Map.of(ERROR, msg)).type(MediaType.APPLICATION_JSON).build());
    }

    /**
     * Read a numeric claim. SmallRye JWT returns custom claims as jakarta.json values
     * (JsonNumber/JsonString), NOT java.lang.Number/String — so a Keycloak `tenant_id: 1`
     * arrives as a JsonNumber. Handle those plus the plain Number/String the test harness
     * mints. A non-integer number (e.g. 1.9) is REJECTED, not truncated — for scoping claims
     * like tenant_id silent truncation could land a user in the wrong tenant. Returns empty on
     * bad input (the caller turns that into a 401).
     */
    private Optional<Long> parseLongClaim(JsonWebToken token, String claim) {
        Object raw = token.getClaim(claim);
        if (raw == null) return Optional.empty();
        if (raw instanceof JsonNumber jn) {
            try {
                return Optional.of(jn.longValueExact());   // throws on a fractional/out-of-range value
            } catch (ArithmeticException e) {
                LOG.warnf("Non-integer %s claim: %s", claim, jn);
                return Optional.empty();
            }
        }
        if (raw instanceof Number n) return Optional.of(n.longValue());
        String s = (raw instanceof JsonString js) ? js.getString() : String.valueOf(raw);
        s = s.strip();
        if (s.isBlank()) return Optional.empty();
        try {
            return Optional.of(Long.parseLong(s));
        } catch (NumberFormatException e) {
            LOG.warnf("Non-numeric %s claim: %s", claim, s);
            return Optional.empty();
        }
    }
}
