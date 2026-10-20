package io.lumnik.hub.connector.rest.pagination;

import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.rest.Urls;

import java.net.URI;
import java.util.Locale;

/**
 * Vets a next-page URL taken from an untrusted upstream response (body {@code $.links.next} /
 * {@code $._links.next.href}, RFC-5988 {@code Link} header) before {@link BodyLinkPaginationStrategy}
 * or {@link LinkHeaderPaginationStrategy} follow it. A hostile or compromised upstream can otherwise put
 * ANY absolute URL in its response — including the cloud metadata address {@code 169.254.169.254} or an
 * internal service — and have it fetched by the hub and ingested into the tenant's data. Throws rather
 * than returning null on refusal: {@code RestEndpointReader#fetchAllPages} treats a null {@code next()}
 * as "no more pages", so a swallowed refusal would look like a clean end of pagination instead of
 * surfacing the attack.
 *
 * <h2>Rules</h2>
 * <ol>
 *   <li>Scheme must be {@code http} or {@code https}, AND must match the CURRENT request's scheme
 *       exactly — a hostile upstream cannot downgrade an {@code https} connector to {@code http} on the
 *       same host, which would otherwise re-send the (re-signed) {@code Authorization} header in
 *       plaintext on the next page.</li>
 *   <li>Host must match (case-insensitively, trailing dot ignored) the host of the CURRENT request. The
 *       first request's host is built straight from the connector's configured {@code base_url}
 *       ({@link PaginationSupport#baseGet}), so requiring every hop to match the PREVIOUS hop's host
 *       transitively pins the whole chain to that configured host — without the strategies needing to
 *       carry {@link EndpointConfig} through {@link PaginationStrategy#next}.</li>
 *   <li>Port must match the EFFECTIVE port of the CURRENT request — the explicit port when the URL has
 *       one, otherwise the scheme's default (80 for {@code http}, 443 for {@code https}). Comparing
 *       effective rather than literal ports is safe only because rule 1 already pinned the scheme equal,
 *       so {@code http://h} and {@code http://h:80} are the same endpoint while {@code https://h} and
 *       {@code https://h:8443} are not. Without this, a same-host next link could still steer the hub to a
 *       different port on that host — e.g. {@code :2375}, the Docker daemon API — which is a standard SSRF
 *       escalation on a host that runs several services.</li>
 * </ol>
 *
 * <h2>Why there is no loopback/private-range check</h2>
 * An earlier version of this guard also refused a literal loopback/link-local/site-local(RFC-1918)/
 * unique-local/wildcard host. That rule was removed: it ran AFTER the same-host check above, so it could
 * only ever evaluate a host already proven IDENTICAL to the operator's own configured {@code base_url}
 * host. The attack this guard exists to stop — a hostile upstream pointing pagination at a DIFFERENT host,
 * e.g. the cloud metadata address or an internal service — is by definition a different host, and rule 2
 * above already refuses it on its own. The range check's only reachable effect was to refuse page 2+
 * forever on a connector whose {@code base_url} is itself a private-range or loopback literal IP — an
 * entirely normal on-prem topology (legacy ERPs routinely sit on RFC-1918 networks with no internal DNS),
 * so it broke a legitimate case for zero added security. Do not reintroduce it: same-host pinning alone is
 * what closes this SSRF, and reaching for an address-range check on top of it is redundant by
 * construction — there is never a host at that point in {@link #vet} that isn't already the operator's own
 * trusted host.
 *
 * <h2>DNS: deliberately never resolved</h2>
 * A hostname is validated ONLY by the same-host rule above — it is never resolved to an address. Resolving
 * here would (a) make this guard's outcome depend on live DNS, so unit tests would be flaky or hang
 * offline, and (b) not even close a hole: the address resolved at validation time is not guaranteed to be
 * the address {@code RestHttpClient} connects to a moment later — a DNS-rebinding attacker can answer the
 * two lookups differently. The same-host rule is the real defense: an attacker's response can only ever
 * point back at the host the operator already configured and trusted, never at an attacker-chosen name.
 * Residual: this guard cannot detect a legitimately-configured {@code base_url} whose OWN hostname resolves
 * to a private/loopback address — that is an operator trust/config decision, not an attacker-controlled
 * redirect, and out of this task's scope.
 */
public final class PaginationUrlGuard {

    private PaginationUrlGuard() {}

    /** Returns {@code nextUrl} unchanged if safe to follow from {@code currentUrl}; throws otherwise. */
    public static String vet(String nextUrl, String currentUrl) {
        URI next = parse(nextUrl);
        URI current = parse(currentUrl);

        String nextScheme = next.getScheme();
        if (nextScheme == null || !("http".equalsIgnoreCase(nextScheme) || "https".equalsIgnoreCase(nextScheme))) {
            throw refuse(nextUrl, "scheme must be http or https");
        }
        if (!nextScheme.equalsIgnoreCase(current.getScheme())) {
            throw refuse(nextUrl, "scheme '" + nextScheme + "' differs from the connector's scheme '" + current.getScheme() + "'");
        }

        String nextHost = normalizeHost(next.getHost(), nextUrl);
        String currentHost = normalizeHost(current.getHost(), currentUrl);
        if (!nextHost.equals(currentHost)) {
            throw refuse(nextUrl, "host '" + next.getHost() + "' differs from the connector's host '" + current.getHost() + "'");
        }

        int nextPort = effectivePort(next);
        int currentPort = effectivePort(current);
        if (nextPort != currentPort) {
            throw refuse(nextUrl, "port '" + nextPort + "' differs from the connector's port '" + currentPort + "'");
        }

        return nextUrl;
    }

    /** The explicit port when present, otherwise the scheme's default (80 http, 443 https). */
    private static int effectivePort(URI uri) {
        int port = uri.getPort();
        if (port != -1) return port;
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static URI parse(String url) {
        try {
            return URI.create(url);
        } catch (IllegalArgumentException e) {
            throw refuse(url, "not a valid URL");
        }
    }

    /** Lower-cased, trailing-FQDN-dot stripped. */
    private static String normalizeHost(String host, String url) {
        if (host == null || host.isBlank()) throw refuse(url, "missing host");
        String h = host.toLowerCase(Locale.ROOT);
        return h.endsWith(".") ? h.substring(0, h.length() - 1) : h;
    }

    /**
     * The link through {@link Urls#forDisplay}: it was read out of the source's body, a signed one
     * carries its token in the query, and this text becomes the run's failure (#1166).
     */
    private static ConnectorException refuse(String url, String reason) {
        return new ConnectorException("Refusing to follow pagination URL '" + Urls.forDisplay(url) + "': " + reason);
    }
}
