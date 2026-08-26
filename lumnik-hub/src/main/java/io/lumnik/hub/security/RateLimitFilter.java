package io.lumnik.hub.security;

import io.lumnik.common.ratelimit.RateLimited;
import io.lumnik.common.tenant.CurrentTenantHolder;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.util.Map;

/**
 * Enforces @RateLimited buckets per (name, tenant) in a fixed 60s window.
 * Runs at USER priority — after authentication and tenant resolution, once per
 * HTTP request (which is why this is a filter and not a CDI interceptor: resource
 * delegation through CDI proxies would multi-count a single request).
 * In-memory, single-node by design; both deploy paths are single-replica today.
 */
@jakarta.ws.rs.ext.Provider
@Priority(Priorities.USER)
@ApplicationScoped
public class RateLimitFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(RateLimitFilter.class);
    private static final FixedWindowRateLimiter LIMITER = new FixedWindowRateLimiter();

    @Context ResourceInfo resourceInfo;
    @Inject Config config;
    @Inject RateLimitMetrics metrics;

    private final Clock clock = Clock.systemUTC();

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

        Long tenant = CurrentTenantHolder.get();
        String key = ann.value() + "|" + (tenant == null ? "system" : tenant);

        var d = LIMITER.tryAcquire(key, rpm, clock.instant().getEpochSecond());
        if (!d.allowed()) {
            // WARN + counter, not debug (audit P3): a 429 storm must be observable server-side.
            // The tenant id is numeric (not PII) and belongs in the log, not in a metric tag.
            LOG.warnf("rate limit exceeded: bucket=%s tenant=%s rpm=%d retryAfter=%ds",
                ann.value(), tenant == null ? "system" : tenant, rpm, d.retryAfterSeconds());
            metrics.recordLimited(ann.value());
            ctx.abortWith(Response.status(429)
                .header("Retry-After", String.valueOf(d.retryAfterSeconds()))
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", "rate limit exceeded"))
                .build());
        }
    }
}
