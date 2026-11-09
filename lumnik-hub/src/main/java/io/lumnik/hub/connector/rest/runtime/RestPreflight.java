package io.lumnik.hub.connector.rest.runtime;

import io.lumnik.common.text.Text;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.domain.ConnectorEndpoint;
import io.lumnik.hub.connector.domain.EffectiveConfig;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.rest.OutboundFailure;
import io.lumnik.hub.connector.rest.RestHttpClient;
import io.lumnik.hub.connector.rest.RestRequest;
import io.lumnik.hub.connector.rest.RestResponse;
import io.lumnik.hub.connector.rest.Snippets;
import io.lumnik.hub.connector.rest.Urls;
import io.lumnik.hub.connector.rest.auth.AuthConfigException;
import io.lumnik.hub.connector.rest.auth.AuthProviderRegistry;
import io.lumnik.hub.connector.rest.auth.AuthSpec;
import io.lumnik.hub.connector.rest.pagination.PaginationFactory;
import io.lumnik.hub.connector.rest.pagination.PaginationStrategy;
import io.lumnik.hub.connector.rest.pagination.PaginationSupport;
import io.lumnik.hub.connector.rest.reliability.ResponseValidator;
import io.lumnik.hub.connector.secret.SecretRefs;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

/**
 * The pre-flight behind {@code lm source rest test}: one request per endpoint, nothing written.
 *
 * <p>It answers one question — "would a run get data out of this endpoint right now?" — and the
 * only way to answer it honestly is to send the request a run would send. So every decision here
 * is delegated to {@link RestEndpointReader}: the effective config, the auth rule, the first
 * request, the body parser, the response validator, the item extraction. Nothing about a request
 * shape is decided twice.
 *
 * <p>That is not tidiness, it is the finding. The pre-flight used to compose its own
 * {@code GET base_url + path} from the connector config alone, and so answered wrong in both
 * directions: it reported 400 on a connector whose declarative headers it never sent (a healthy
 * connector, failing its own pre-flight), and 200 on a GraphQL endpoint it never queried — it had
 * GET-ed the service root and read the banner as success.
 *
 * <p>The one thing it deliberately does NOT borrow is
 * {@link io.lumnik.hub.connector.rest.ResponseStatusGuard}: that turns a non-2xx into a thrown
 * failure so a run can never read it as data. Here, showing the operator the 401 IS the point.
 */
@ApplicationScoped
public class RestPreflight {

    @Inject PaginationFactory pagination;
    @Inject AuthProviderRegistry authReg;
    @Inject RestHttpClient http;
    @Inject SecretRefs secrets;

    /**
     * What one endpoint answered. {@code ok} means the request a run would send came back readable:
     * a 2xx whose body passes the same validator the run applies (a GraphQL 200 carrying
     * {@code errors[]} is not ok). {@code items} is what the run's own pagination strategy would
     * have extracted from that page — a 2xx with zero items is precisely the shape that used to
     * land as a green empty sync, so a pre-flight owes the operator that number.
     *
     * <p>{@code url} is shown on purpose, through {@link Urls#forDisplay}: host and path, never the
     * userinfo a {@code base_url} may carry nor the query an endpoint path may (#1166). Applied here,
     * once, so no verdict can be built around it.
     */
    public record Verdict(String endpoint, String method, String url, int status,
                          boolean ok, int items, String detail, String sample) {
        public Verdict {
            url = Urls.forDisplay(url);
        }
    }

    public Verdict check(Connector c, ConnectorEndpoint ep) {
        Map<String, Object> cfg = EffectiveConfig.of(c, ep);

        // Two refusals, not one, because they know different amounts. Both are named rather than
        // thrown, so a pre-flight over several endpoints reports this one and carries on.
        RestRequest req;
        try {
            req = request(cfg, ep);
        } catch (Exception unbuildable) {
            // Nothing was composed — an unknown pagination kind, a config block that is not one.
            // The endpoint row's own method is all that is known, and there is no URL to report.
            // Empty, never null: this record is a wire contract read by a typed Go struct, and it
            // carries a string in every other field. "No request was composed" is said by status 0,
            // which the CLI renders as "-".
            return new Verdict(ep.getName(), ep.getMethod(), "", 0, false, 0, describe(unbuildable), "");
        }

        RestConnection conn;
        try {
            conn = RestEndpointReader.connect(cfg, owner(c), authReg, http, secrets);
        } catch (Exception refused) {
            // The credential is what failed, and the request was already composed: report the shape
            // a run would have sent. Falling back to the endpoint row here would print GET for a
            // GraphQL endpoint this very command POSTs to — the gap this class exists to close,
            // on the one path an operator reaches by asking "is my token valid?".
            return refused(ep, req, refused);
        }

        RestResponse resp;
        try {
            ResponseValidator validator = RestEndpointReader.validatorFor(cfg);
            resp = conn.send(req, signed -> conn.client().exchange(signed, validator));
        } catch (InterruptedException cancelled) {
            throw interrupted(cancelled);
        } catch (AuthConfigException refused) {
            // A credential renewed after the source's 401 and refused in turn (#1393): its sentence is ours.
            return refused(ep, req, refused);
        } catch (Exception unreachable) {
            // The kind, never the message: a JDK message can quote the URL, credential included
            // (#1164). The other three catches keep describe(): their messages are ours.
            return new Verdict(ep.getName(), req.method(), req.url(), 0, false, 0,
                OutboundFailure.describe(unreachable), "");
        }

        String sample = Snippets.bound(resp.body());
        boolean http2xx = resp.status() / 100 == 2;
        if (!http2xx) {
            return new Verdict(ep.getName(), req.method(), req.url(), resp.status(),
                false, 0, refusalOf(cfg, resp), sample);
        }

        try {
            RestEndpointReader.validatorFor(cfg).validate(resp);
            RestResponse parsed = RestEndpointReader.bodyParserFor(cfg).reparse(resp);
            Map<String, Object> pagCfg = RestEndpointReader.mapConfig(cfg, "pagination");
            // The first page a run would refuse (response_path reads nothing, or not a list) is not ok here
            // either (#1925): the run names the same sentence, behind the endpoint's name.
            String firstPage = PaginationSupport.firstPageRefusal(parsed.json(), RestEndpointReader.str(pagCfg.get("response_path")));
            if (firstPage != null) {
                return new Verdict(ep.getName(), req.method(), req.url(), resp.status(), false, 0, firstPage, sample);
            }
            int items = pagination.build(pagCfg).items(parsed).size();
            // The count a run would read off this page, so a header it refuses is not answered ok (#1866).
            PaginationSupport.declaredItems(parsed, RestEndpointReader.str(pagCfg.get("total_path")),
                RestEndpointReader.str(pagCfg.get("total_header")));
            return new Verdict(ep.getName(), req.method(), req.url(), resp.status(),
                true, items, "", sample);
        } catch (Exception unreadable) {
            // 2xx, and still not data: a GraphQL errors[] envelope, a SOAP fault, an HTML page
            // served with 200. The run would fail the chunk here; the pre-flight says so first.
            return new Verdict(ep.getName(), req.method(), req.url(), resp.status(),
                false, 0, describe(unreadable), sample);
        }
    }

    /**
     * A non-2xx, in the run's own words (#1861): the validator reads the body first, so a SOAP Fault on HTTP 500 is
     * named. A body it does not recognise, and GraphQL (whose validator leaves a non-2xx to its status), give
     * {@code HTTP <status>}.
     */
    private static String refusalOf(Map<String, Object> cfg, RestResponse resp) {
        try {
            RestEndpointReader.validatorFor(cfg).validate(resp);
            return "HTTP " + resp.status();
        } catch (RuntimeException named) {
            return describe(named);
        }
    }

    /** The credential refused: no status, the request a run would have sent, the refusal in our own words. */
    private static Verdict refused(ConnectorEndpoint ep, RestRequest req, Exception refused) {
        return new Verdict(ep.getName(), req.method(), req.url(), 0, false, 0, describe(refused), "");
    }

    /** The first request of a read — the run's, composed by the run's own code. */
    RestRequest request(Map<String, Object> cfg, ConnectorEndpoint ep) {
        PaginationStrategy pag = pagination.build(RestEndpointReader.mapConfig(cfg, "pagination"));
        return RestEndpointReader.firstRequest(pag, cfg, ep, secrets::lookup);
    }

    /** The credential a run would present — same rule, same default ({@code none} when undeclared). */
    AuthSpec authSpec(Map<String, Object> cfg, AuthSpec.Owner owner) {
        return RestEndpointReader.authSpecFor(cfg, owner);
    }

    // Entity-level overloads: the whole chain from the two rows an operator names, merge included.
    RestRequest request(Connector c, ConnectorEndpoint ep) { return request(EffectiveConfig.of(c, ep), ep); }
    AuthSpec authSpec(Connector c, ConnectorEndpoint ep)   { return authSpec(EffectiveConfig.of(c, ep), owner(c)); }

    private static AuthSpec.Owner owner(Connector c) {
        return new AuthSpec.Owner(c.getTenantId(), c.getId());
    }

    /**
     * An interrupt is a cancelled request, not an answer about the upstream: reported as a verdict
     * it would tell the operator their endpoint refused when nobody ever asked it, and caught as a
     * plain {@code Exception} it would drop the thread's interrupt flag. Same handling as
     * {@code RestEndpointReader.fetchAllPages} — re-interrupt, then fail loudly.
     */
    private static ConnectorException interrupted(InterruptedException cancelled) {
        Thread.currentThread().interrupt();
        return new ConnectorException("REST pre-flight interrupted", cancelled);
    }

    private static String describe(Throwable t) {
        String msg = t.getMessage();
        return Text.blank(msg) ? t.getClass().getSimpleName() : msg;
    }
}
