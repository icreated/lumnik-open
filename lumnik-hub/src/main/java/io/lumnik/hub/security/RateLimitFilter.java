package io.lumnik.hub.security;

import io.lumnik.common.ratelimit.RateLimited;
import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.tenant.TenantContextFilter;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Clock;

/**
 * Enforces @RateLimited buckets per (name, tenant, user), under a per-(name, tenant) ceiling, in a fixed 60s window.
 * Runs at USER priority — after authentication and tenant resolution, once per
 * HTTP request (which is why this is a filter and not a CDI interceptor: resource
 * delegation through CDI proxies would multi-count a single request).
 * In-memory, single-node by design; both deploy paths are single-replica today.
 */
@Provider
@Priority(Priorities.USER)
@ApplicationScoped
@Startup   // a negative tenant-factor refuses the boot, not the first limited request
public class RateLimitFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(RateLimitFilter.class);
    private static final FixedWindowRateLimiter LIMITER = new FixedWindowRateLimiter();

    @Context ResourceInfo resourceInfo;
    @Inject Config config;
    @Inject RateLimitMetrics metrics;

    /** Each tenant's ceiling over its users is rpm x this (#1996); 0 switches the ceiling off. */
    @ConfigProperty(name = "lumnik.ratelimit.tenant-factor", defaultValue = "5")
    int tenantFactor;

    private final Clock clock = Clock.systemUTC();

    /** A call's verdict; {@code byCeiling} when the tenant's ceiling, not the caller's own budget, refused it. */
    record Verdict(FixedWindowRateLimiter.Decision decision, boolean byCeiling) {}

    /**
     * The caller's own share first, then the tenant's ceiling above it (#1996): one user looping a door spends their
     * share, not the tenant's. A refused call spends neither: a call the ceiling refuses gives its user's slot back.
     * A request with no user (SCIM's bearer, a system call) has a budget of its own per tenant, {@code rpm}, apart
     * from the users' ceiling.
     */
    static Verdict decide(FixedWindowRateLimiter limiter, String bucket, String tenant, Long user,
                          int rpm, int tenantRpm, long now) {
        String tenantKey = bucket + "|" + tenant;
        String ownKey = tenantKey + "|" + (user == null ? "-" : user);
        var own = limiter.tryAcquire(ownKey, rpm, now);
        if (user == null || !own.allowed()) return new Verdict(own, false);
        var ceiling = limiter.tryAcquire(tenantKey, tenantRpm, now);
        if (!ceiling.allowed()) limiter.release(ownKey, now);
        return new Verdict(ceiling, !ceiling.allowed());
    }

    /** rpm x the factor, saturated: a product past int must not wrap negative and switch the ceiling off. */
    static int tenantRpm(int rpm, int factor) {
        return (int) Math.min((long) rpm * factor, Integer.MAX_VALUE);
    }

    @PostConstruct
    void refuseANegativeFactor() {
        if (tenantFactor < 0) throw new IllegalStateException(
            "lumnik.ratelimit.tenant-factor must be 0 (no tenant ceiling) or more, not " + tenantFactor);
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (resourceInfo == null || resourceInfo.getResourceClass() == null) return;
        RateLimited ann = resourceInfo.getResourceMethod() != null
            ? resourceInfo.getResourceMethod().getAnnotation(RateLimited.class) : null;
        if (ann == null) ann = resourceInfo.getResourceClass().getAnnotation(RateLimited.class);
        if (ann == null) return;

        int rpm = config.getOptionalValue("lumnik.ratelimit." + ann.value() + ".rpm", Integer.class)
            .orElseGet(() -> config.getOptionalValue("lumnik.ratelimit.default.rpm", Integer.class)
            .orElse(60));
        Long tenantId = CurrentTenantHolder.get();
        String tenant = tenantId == null ? "system" : String.valueOf(tenantId);
        Long user = TenantContextFilter.currentUser();
        int tenantRpm = tenantRpm(rpm, tenantFactor);
        var verdict = decide(LIMITER, ann.value(), tenant, user, rpm, tenantRpm, clock.instant().getEpochSecond());
        var d = verdict.decision();
        if (!d.allowed()) {
            // WARN + counter, not debug (audit P3): a 429 storm must be observable server-side.
            // The tenant and user ids are numeric (not PII) and belong in the log, not in a metric tag.
            LOG.warnf("rate limit exceeded: bucket=%s tenant=%s user=%s limit=%s rpm=%d retryAfter=%ds",
                ann.value(), tenant, user == null ? "-" : user, verdict.byCeiling() ? "tenant" : "own",
                verdict.byCeiling() ? tenantRpm : rpm, d.retryAfterSeconds());
            metrics.recordLimited(ann.value());
            ctx.abortWith(ApiErrors.status(429, "rate limit exceeded")
                .header("Retry-After", String.valueOf(d.retryAfterSeconds()))
                .build());
        }
    }
}
