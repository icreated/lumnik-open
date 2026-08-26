package io.lumnik.hub.security;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The observability floor for rate limiting (audit P3): before this counter, a 429 storm was
 * completely silent server-side — the only trace was a debug-level log line, so "attack or
 * busy Monday?" had no observable answer. Tagged by bucket only: the tenant goes into the
 * WARN log line, never into a tag (unbounded tag cardinality is the metric anti-pattern
 * this floor deliberately avoids).
 */
@ApplicationScoped
public class RateLimitMetrics {

    @Inject MeterRegistry registry;

    /** A request was refused with 429 on this bucket. */
    public void recordLimited(String bucket) {
        registry.counter("lumnik_rate_limited_total", "bucket", bucket).increment();
    }
}
