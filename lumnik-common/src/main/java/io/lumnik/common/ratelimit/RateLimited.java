package io.lumnik.common.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a JAX-RS resource (class or method) as rate-limited under the given bucket name.
 * Enforced by the hub's RateLimitFilter (a ContainerRequestFilter), NOT a CDI interceptor:
 * resources delegate to each other through CDI proxies (user chat → platform chat → search),
 * and an interceptor would count one HTTP request several times. A plain marker keeps
 * lumnik-common CDI-free (module rule).
 *
 * Limit config: {@code lumnik.ratelimit.<name>.rpm}, fallback {@code lumnik.ratelimit.default.rpm}
 * (code default 60). {@code 0} disables. Requests are counted per (name, tenant) in a fixed
 * 60-second window, in memory — single-node by design (both current deploy paths are
 * single-replica); revisit only when a multi-replica deploy is real.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimited {

    /** Bucket name, e.g. "user-chat" — also the config key segment. */
    String value();
}
