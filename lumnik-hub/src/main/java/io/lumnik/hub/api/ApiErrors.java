package io.lumnik.hub.api;

import io.lumnik.common.security.ScopeGrants;
import io.lumnik.common.text.Text;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The one shape a hub JSON error body takes: {@code {"error": "…"}}. Single-sourced so the
 * scope refusal — a FROZEN cross-language contract the PWA, {@code lm} and the OTK oracle
 * branch on — cannot drift by a typo in one of the doors that serve it.
 *
 * The decision and its string live in {@link ScopeGrants} (framework-free); this is just the
 * JAX-RS dress. {@code lumnik-rag} carries its own twin for the same reason.
 */
public final class ApiErrors {

    /** The body key. Package-private with {@link #error}: outside this package a body is built only
     *  through {@link #status} and its family, so it always goes out as JSON (#1646). */
    static final String KEY = "error";

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
        return status(403, ScopeGrants.REFUSAL).build();   // status() leaves the WARN
    }

    /**
     * The one WARN a scope refusal leaves, written where the refusal is built — {@link #status}, which
     * every helper goes through — so that no door can refuse silently, whichever helper it reaches for. Same register as the role gate's {@code Access denied: required=…}: the
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
        return status(401, reason).build();
    }

    /**
     * A 403 that says why, in the same dress — for a refusal that is NOT the scope one. Never stand
     * in for {@link #scopeNotGranted()}: the PWA branches on that exact string, so a role or
     * account refusal must not wear it (#1646).
     */
    public static Response forbidden(String why) {
        return status(403, why).build();
    }

    /**
     * A 404 that says what is missing, in the same dress — a bare {@code Response.status(404)}
     * reaches {@code lm} as "no details from the server".
     */
    public static Response notFound(String what) {
        return status(404, what).build();
    }

    /**
     * A 409 that says which name is taken, in the same dress.
     */
    public static Response conflict(String what) {
        return status(409, what).build();
    }

    /** {@link #conflict(String)}'s own twin for the few 409s that carry one extra field (a hint id)
     *  alongside the message (code review finding) — same dress, via {@link #error(String, String,
     *  Object)}, so a future change to it is never missed by a hand-built body. */
    public static Response conflict(String what, String extraKey, Object extraValue) {
        return status(409, what, extraKey, extraValue).build();
    }

    /**
     * A 400 that says what the caller got wrong, in the same dress.
     */
    public static Response badRequest(String why) {
        return status(400, why).build();
    }

    /**
     * The dress itself, for any status: {@code {"error": why}} typed JSON. Every helper above is this
     * plus a status; a status with no helper of its own (422, 429, 5xx) calls it directly. A builder,
     * not a {@code Response}, so a door can still add a header ({@code Retry-After}).
     *
     * <p>Why the type is always written: a door that assembled the body by hand tended to forget it,
     * and the refusal then negotiated its dress from the resource's {@code @Produces} — YAML, text or
     * markdown on some doors. With {@link #error} package-private, this is the only way to build the
     * body outside this package; a door can still override the type on the builder, but only on
     * purpose, never by omission (#1646).
     *
     * <p>A blank {@code why} — a door passing {@code e.getMessage()} of an exception that has none —
     * says the status's own reason phrase rather than trading the refusal for an NPE-shaped 500.
     */
    public static Response.ResponseBuilder status(int code, String why) {
        return status(code, why, null, null);
    }

    /** {@link #status(int, String)} for a body that carries one extra field beside the message (a hint,
     *  a run id, the raw model output), after it and possibly null. */
    public static Response.ResponseBuilder status(int code, String why, String extraKey, Object extraValue) {
        String message = Text.blank(why) ? reasonPhrase(code) : why;
        if (code == 403 && ScopeGrants.REFUSAL.equals(message)) logScopeRefusal(LOG);
        Map<String, Object> body = extraKey == null ? error(message) : error(message, extraKey, extraValue);
        return Response.status(code).type(MediaType.APPLICATION_JSON).entity(body);
    }

    private static String reasonPhrase(int code) {
        Response.Status known = Response.Status.fromStatusCode(code);
        return known != null ? known.getReasonPhrase() : "HTTP " + code;
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
            throw new BadRequestException(badRequest(Text.blank(why) ? "bad request" : why), e);
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

    /**
     * A single-valued filter read from the raw query: absent is null; repeated or blank is an
     * {@link IllegalArgumentException} for {@link #orBadRequest}. Raw, because a bare {@code x=}
     * reaches a {@code @QueryParam} as null — "absent" — and would widen the read in silence; once,
     * because a second value would otherwise be dropped. The inbox and the workflow tape (#1356, #1361).
     */
    public static String once(MultivaluedMap<String, String> query, String key) {
        return switch (query.get(key)) {
            case null -> null;
            case List<String> v when v.size() > 1 -> throw new IllegalArgumentException(key + " may be given once");
            case List<String> v when Text.blank(v.getFirst()) ->
                throw new IllegalArgumentException(key + " must not be blank");
            case List<String> v -> v.getFirst();
        };
    }

    /** The {@code {"error": message}} body. */
    static Map<String, Object> error(String message) {
        return Map.of(KEY, message);
    }

    /** {@link #error(String)}'s own twin for a body carrying one extra field (a hint id, a reason)
     *  alongside the message — in that order, and the value may be null. */
    static Map<String, Object> error(String message, String extraKey, Object extraValue) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(KEY, message);
        body.put(extraKey, extraValue);
        return Collections.unmodifiableMap(body);
    }
}
