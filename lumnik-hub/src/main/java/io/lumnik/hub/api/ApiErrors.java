package io.lumnik.hub.api;

import io.lumnik.common.security.ScopeGrants;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The one shape a hub JSON error body takes: {@code {"error": "…"}}. Single-sourced so the
 * métier-scope refusal — a FROZEN cross-language contract the PWA, {@code lm} and the OTK oracle
 * branch on — cannot drift by a typo in one of the doors that serve it.
 *
 * The decision and its string live in {@link ScopeGrants} (framework-free); this is just the
 * JAX-RS dress. {@code lumnik-rag} carries its own twin for the same reason.
 */
public final class ApiErrors {

    /** The body key — only for the few bodies carrying more than the message (a hint, an {@code ok}). */
    public static final String KEY = "error";

    private static final Logger LOG = Logger.getLogger(ApiErrors.class);

    private ApiErrors() {}

    /**
     * The uniform refusal: {@code 403 {"error":"scope not granted"}}, with its content type set
     * explicitly.
     *
     * <p><b>What that call is actually worth, measured.</b> Two view resources declare JSON
     * <em>and</em> YAML at the class level, so a refusal served from one of them is NEGOTIATED
     * rather than fixed — which is why the type is written here. Removing the call changes
     * nothing today: a {@code Map} entity has no {@code application/yaml} writer in this app
     * (every yaml response the hub serves is a String carrying its own type), so the refusal
     * falls back to JSON on its own. This line is what does not depend on that accident — the
     * day a yaml writer is registered, negotiation starts succeeding and it is the only thing
     * holding the frozen contract in JSON. {@code ScopeRefusalOnTheWireIT} reads the dress off
     * the socket under {@code Accept: application/yaml}.
     */
    public static Response scopeNotGranted() {
        logScopeRefusal(LOG);
        return Response.status(403).type(MediaType.APPLICATION_JSON)
            .entity(error(ScopeGrants.REFUSAL)).build();
    }

    /**
     * The one WARN a scope refusal leaves, written where the refusal is built so that no door can
     * refuse silently. Same register as the role gate's {@code Access denied: required=…}: the
     * caller's grants, never row data, never the token. Tenant and user ride the MDC. The rag twin
     * writes the same line through its own logger, so it reads the same from both modules.
     */
    public static void logScopeRefusal(Logger log) {
        log.warnf("Access denied: %s, granted=%s", ScopeGrants.REFUSAL, ScopeGrants.grantedForLog());
    }

    /**
     * The uniform 401: {@code {"error": …}} in the same dress as {@link #scopeNotGranted()}. Both
     * Require* guards answer through here — {@code RequireTenant} used to build its own without a
     * content type, which is the gap the 403 helper's javadoc above already describes.
     */
    public static Response unauthorized(String reason) {
        return Response.status(401).type(MediaType.APPLICATION_JSON).entity(error(reason)).build();
    }

    /**
     * A 404 that says what is missing, in the same dress — a bare {@code Response.status(404)}
     * reaches {@code lm} as "no details from the server".
     */
    public static Response notFound(String what) {
        return Response.status(404).type(MediaType.APPLICATION_JSON).entity(error(what)).build();
    }

    /**
     * A 409 that says which name is taken, in the same dress — the missing member of the family
     * above. Two of the hub's hand-built 409s already omit the content type, which is the drift
     * this class exists to end (see {@link #scopeNotGranted()}); the rest repeat these four lines.
     */
    public static Response conflict(String what) {
        return Response.status(409).type(MediaType.APPLICATION_JSON).entity(error(what)).build();
    }

    /** {@link #conflict(String)}'s own twin for the few 409s that carry one extra field (a hint id)
     *  alongside the message (code review finding) — same dress, via {@link #error(String, String,
     *  Object)}, so a future change to it is never missed by a hand-built body. */
    public static Response conflict(String what, String extraKey, Object extraValue) {
        return Response.status(409).type(MediaType.APPLICATION_JSON).entity(error(what, extraKey, extraValue)).build();
    }

    /**
     * A 400 that says what the caller got wrong, in the same dress — the last member of the family,
     * added for {@link ConfigValidationExceptionMapper}. Around the hub some forty 400s still build
     * these lines by hand; most pass {@link #error} but omit the content type, which is how a refusal
     * ends up in a different dress from its 403 neighbour (see {@link #scopeNotGranted()}). Reaching
     * for this helper is what stops that drift spreading.
     */
    public static Response badRequest(String why) {
        return Response.status(400).type(MediaType.APPLICATION_JSON).entity(error(why)).build();
    }

    /**
     * Runs {@code step}; an {@link IllegalArgumentException} it throws is the caller's 400, its message
     * the body ({@link #badRequest}). It is how a parser, resolver or filter states that the input is
     * wrong, and the manifest, view and source doors all answered it with the same hand-written
     * try/catch (#1293).
     *
     * <p>Thrown, not returned, so it serves a door that returns an entity as well as one that returns
     * a {@code Response}; {@link BadRequestExceptionMapper} passes the dressed response through
     * untouched. Only {@code step} is covered: an IAE raised anywhere else in the door stays the
     * defect it is, never a 400 that echoes its message. Thrown inside a {@code @Transactional} door it
     * rolls the transaction back, so call it before the door writes.
     */
    public static <T> T orBadRequest(Supplier<T> step) {
        try {
            return step.get();
        } catch (IllegalArgumentException e) {
            // BadRequestExceptionMapper's fallback: badRequest(null) would be Map.of(KEY, null), a 500.
            String why = e.getMessage();
            throw new BadRequestException(badRequest(why == null || why.isBlank() ? "bad request" : why), e);
        }
    }

    /** {@link #orBadRequest} for a check that returns nothing. */
    public static void checkOrBadRequest(Runnable check) {
        orBadRequest(() -> {
            check.run();
            return null;
        });
    }

    /**
     * Parses an id that arrived as a query or path string, refusing anything else with the same
     * JSON 400 body — {@code {"error":"invalid UUID: …"}} — every platform door already answers.
     *
     * <p>A door that cannot parse a FILTER must refuse it, never ignore it: silently dropping an
     * unreadable {@code ?connector=} answers about every connector under a heading that names
     * one.
     */
    public static UUID uuidOrRefuse(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(badRequest("invalid UUID: " + s));
        }
    }

    /** The {@code {"error": message}} body. */
    public static Map<String, Object> error(String message) {
        return Map.of(KEY, message);
    }

    /** {@link #error(String)}'s own twin for a body carrying one extra field (a hint id, a reason)
     *  alongside the message. */
    public static Map<String, Object> error(String message, String extraKey, Object extraValue) {
        return Map.of(KEY, message, extraKey, extraValue);
    }
}
