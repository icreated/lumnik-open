package io.lumnik.hub.connector.rest;

import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.exceptions.TransientFailureException;
import io.lumnik.hub.connector.rest.reliability.RetryPolicy;

import java.util.Set;

/**
 * The one rule saying whether an HTTP response may be read AS DATA. Every caller that turns a
 * response body into rows, or into an inferred schema, MUST pass it through here first.
 *
 * <p>Why it has to exist at all: nothing downstream inspects {@code status()}. A non-2xx body —
 * an HTML error page, a JSON {@code {"error": …}}, a 3xx the client was told never to follow —
 * simply carries no items path, so the pagination strategies read <b>zero items</b> from it and the
 * chunk loop treats that as "the page was empty, we are done". A source answering 500 to everything
 * therefore produced a {@code Completed} run with {@code in=0 out=0 skipped=0} and not one WARN:
 * a dead source reported as a successful empty sync. An expired token (401, never retried) did the
 * same, silently.
 *
 * <p>The classification is not invented here — it is the one the SOAP lane already used, lifted to
 * where every request shape reaches it:
 * <ul>
 *   <li><b>3xx</b> — refused by name, with the {@code Location} it declined to follow.
 *       {@code followRedirects(NEVER)} makes a redirect an ordinary response; chasing it would be a
 *       second, unvetted path to an arbitrary host ({@code PaginationUrlGuard} only vets URLs read
 *       out of a body/header, never a {@code Location} the JDK client would follow on its own).</li>
 *   <li><b>Retryable</b> (whatever {@link RetryPolicy#defaults()} declares: 408/429/5xx) —
 *       {@link TransientFailureException}, so the chunk is retried later rather than dead-lettered
 *       on an upstream hiccup.</li>
 *   <li><b>Everything else</b> (401/403/404/422 …) — a permanent {@link ConnectorException}. These
 *       are misconfigurations; the retry executor does not retry them, so neither may this. A fast
 *       named failure beats a chunk retried forever against a credential that will not come back.</li>
 * </ul>
 *
 * <p>The one caller that must NOT use this is the endpoint test/dry-run, which exists purely to
 * report {@code status} and {@code ok} back to a human — showing the operator the 401 IS the point
 * there.
 */
public final class ResponseStatusGuard {

    /**
     * Derived from the retry policy rather than restated, so the two cannot drift: the day a status
     * stops being retried, it stops being classified transient here, in the same edit.
     */
    private static final Set<Integer> RETRYABLE = RetryPolicy.defaults().retryOn();

    private ResponseStatusGuard() {}

    /** @throws TransientFailureException on a retryable status; {@link ConnectorException} otherwise. */
    public static void refuseNonSuccess(RestResponse resp) {
        int status = resp.status();
        if (status / 100 == 2) return;

        if (status >= 300 && status < 400) {
            throw new ConnectorException("upstream returned a redirect (" + status + ") to '"
                + resp.header("Location").orElse("<no Location header>")
                + "' — refusing to follow it or ingest its body as data");
        }

        String detail = " — refusing to ingest its body as data: " + Snippets.bound(resp.body());
        if (RETRYABLE.contains(status)) {
            throw new TransientFailureException(
                "upstream returned HTTP " + status + " (transient)" + detail, null);
        }
        throw new ConnectorException("upstream returned HTTP " + status + " (permanent)" + detail);
    }
}
