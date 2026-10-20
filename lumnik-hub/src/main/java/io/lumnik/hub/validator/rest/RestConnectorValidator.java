package io.lumnik.hub.validator.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lumnik.hub.connector.rest.reliability.RateLimiterRegistry;
import io.lumnik.hub.connector.registry.ConnectorTypeId;
import io.lumnik.hub.connector.rest.DottedPath;
import io.lumnik.hub.connector.rest.pagination.CustomPaginationStrategy;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec;
import io.lumnik.hub.connector.rest.pagination.preset.PaginationPresetRegistry;
import io.lumnik.hub.validator.*;
import io.lumnik.hub.validator.ConfigDef.Importance;
import static io.lumnik.hub.validator.JsonNodes.absent;
import static io.lumnik.hub.validator.JsonNodes.blankOrNull;
import io.lumnik.hub.validator.ConfigDef.Type;
import io.lumnik.hub.validator.ValidationError.Severity;
import io.lumnik.hub.validator.common.CronField;
import io.lumnik.hub.validator.common.WindowField;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static io.lumnik.hub.validator.ConfigDefValidators.*;

@ApplicationScoped
@ConnectorTypeId("rest-generic")
public class RestConnectorValidator implements ConnectorValidator {

    @Inject PaginationPresetRegistry presets;

    /** Required preset overrides are interpolated into JSONPaths (e.g. {@code $._embedded.{items}}),
     *  so restrict them to a simple identifier — a value can't inject JSONPath metacharacters. */
    private static final Pattern SIMPLE_OVERRIDE = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*");

    private static final String PAGINATION = "pagination";
    private static final String RESPONSE_PATH = "response_path";
    private static final String CURSOR_BINDING = "cursor_binding";
    private static final String RELAY = "relay";
    private static final String VARIABLE = "variable";

    @Override public String kind() { return "rest-generic"; }

    @Override
    public ConfigDef configDef() {
        // base_url + endpoints live at connector level; auth is OPTIONAL (omitted => `none` at
        // runtime) and target lives PER ENDPOINT (see EndpointsBlockValidator + the apply handler),
        // so neither auth nor target is required here. Auth, when present, is validated in
        // validateCrossField via AuthBlockValidator.
        return new ConfigDef()
            .requireCustom("base_url", Type.STRING, Importance.HIGH,
                "Base URL of the API (without trailing slash)", isUrl())
            .require("endpoints", Type.LIST, Importance.HIGH,
                "Endpoint definitions (at least one)")
            // The runtime reads default_rate_limit (a "count/window" string, window unit s|m|h),
            // not an integer rps. timeout_ms was advertised but never honored — dropped rather than
            // mislead (re-add only when the HTTP client actually consumes it).
            .optional("default_rate_limit", Type.STRING, Importance.MEDIUM,
                "Max request rate across all endpoints, e.g. \"100/s\" (count/window; unit s|m|h)", "100/s")
            .optional("schedule", Type.STRING, Importance.MEDIUM,
                "Cron expression for recurring runs — the hub scheduler fires it, evaluated in UTC", null)
            // The four keys the door reads and this def did not list, declared so the closed sweep
            // does not accuse them. `auth` carries its sub-def: AuthBlockValidator's key set was
            // already swept from validateAuth by hand, which is the workaround ConfigDef.optionalNested
            // replaced (#472) — the declaration also gives the block its missing TYPE check, and a
            // non-object auth used to validate clean and then break the apply door with a 500.
            .optionalNested("auth", Importance.MEDIUM,
                "Authentication for every endpoint (omit => none)", AuthBlockValidator.configDef())
            .optionalHinted("headers", Type.LIST, Importance.MEDIUM,
                "Static headers sent on every request, [{name, value | value_env}]", null,
                "headers is a list of { name, value|value_env } objects")
            .optional("defaults", Type.NESTED, Importance.LOW,
                "Per-endpoint defaults merged into each endpoint", null)
            .optional("graphql_path", Type.STRING, Importance.LOW,
                "Path appended to base_url for GraphQL endpoints (default /graphql)", null)
            // schedule's twin, read by the SHARED apply preamble (ApplyUpsert); declared and
            // checked through WindowField, which owns both wordings for all four connector types.
            .optional("window", Type.STRING, Importance.MEDIUM, WindowField.DOC, null)
            .closed();
    }

    @Override
    public List<ValidationError> validateCrossField(JsonNode spec) {
        List<ValidationError> errs = new ArrayList<>();
        validateAuth(spec.path("auth"), errs);
        validateEndpoints(spec.path("endpoints"), errs);
        validateHeaders(spec.path("headers"), errs);
        CronField.check(spec.path("schedule"), errs,
            "Example: schedule: \"0 6 * * *\" (daily at 06:00 UTC)");
        JsonNode rate = spec.path("default_rate_limit");
        // isTextual, not just present: the declaration refuses a non-string, and reading
        // asText() off a number here would report one mistake twice.
        if (rate.isTextual()) {
            // validator == runtime: the same reading the HTTP client does on the first request,
            // so `lm apply` never accepts a rate the run would only choke on later.
            RateLimiterRegistry.describeProblem(rate.asText())
                .ifPresent(msg -> errs.add(new ValidationError("spec.default_rate_limit",
                    ValidationError.Severity.ERROR, msg,
                    List.of("Example: default_rate_limit: \"100/s\" (count/window; unit s|m|h)"))));
        }
        WindowField.check(spec.path("window"), errs);
        return errs;
    }

    /** auth is optional; validate the block only when present (omitting it => `none` at runtime). */
    private void validateAuth(JsonNode auth, List<ValidationError> errs) {
        // The block's key set and shape are the declaration's job now (configDef), so only the
        // cross-field rule is left here. A non-object cannot reach the rule below anyway — a
        // MissingNode `kind` never equals "bearer" — so this guard is armour, not logic.
        if (!auth.isObject()) return;
        // bearer accepts either an env-var name (token_env) or an inline value (token_value).
        // Missing, explicit null, or blank all count as absent (NullNode.asText() is "null").
        // The secret itself, in a file the integrator is told to commit and review. Not the same
        // hazard as jdbc's `password` — BearerAuthProviderFactory reads token_env FIRST and only
        // falls back to the literal when no reference is named, so nothing is silently overridden
        // — but it is the same secret in the same repository, and the docs' CI grep is the only
        // thing that stood between it and a git history (#530).
        //
        // Under `kind: bearer` only: the field is declared optional for every kind, and nothing
        // else reads it, so elsewhere this sentence would name a bearer token that is not one.
        // A secret in a key the runtime ignores is a different finding — dead knobs have their own
        // sweep — and the CI grep the docs give bans the line whatever the kind.
        if ("bearer".equals(auth.path("kind").asText())
                && !blankOrNull(auth.path("token_value"))) {
            errs.add(new ValidationError("spec.auth.token_value", Severity.WARNING,
                "literal bearer token in the manifest — this is the secret itself, not a name, "
                    + "and a manifest is meant to be committed",
                List.of("Remove it and name the secret: token_env: <NAME> "
                            + "(`lm secret set <NAME> …`, else an env var set on the hub)",
                    "Keep it only in a manifest that is never committed — dev/test")));
        }
        if ("bearer".equals(auth.path("kind").asText())
                && blankOrNull(auth.path("token_env"))
                && blankOrNull(auth.path("token_value"))) {
            errs.add(new ValidationError("spec.auth.token_env", Severity.ERROR,
                "bearer auth requires token_env (or inline token_value)",
                List.of("Use `lm secret set <NAME> ...` then reference via token_env: <NAME>")));
        }
    }

    private void validateEndpoints(JsonNode endpoints, List<ValidationError> errs) {
        if (!endpoints.isArray()) return;
        if (endpoints.isEmpty()) {
            errs.add(new ValidationError("spec.endpoints", Severity.ERROR,
                "at least one endpoint is required",
                List.of("Add an endpoint: { id: 'customers', path: '/v1/customers', method: 'GET' }")));
            return;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < endpoints.size(); i++) {
            validateEndpoint(endpoints.get(i), "spec.endpoints[" + i + "]", seen, errs);
        }
    }

    /** If pagination.kind names a preset, validate its required overrides and return the endpoint with
     *  the preset expanded to a concrete strategy; otherwise return the endpoint unchanged. */
    private JsonNode expandPresetIfAny(JsonNode endpoint, String basePath, List<ValidationError> errs) {
        JsonNode pagNode = endpoint.path(PAGINATION);
        String pagKind = pagNode.path("kind").asText();
        if (!presets.isPreset(pagKind)) {
            return endpoint;
        }
        for (String req : presets.requiredOverrides(pagKind)) {
            JsonNode v = pagNode.path(req);
            if (blankOrNull(v)) {
                errs.add(new ValidationError(basePath + ".pagination." + req, Severity.ERROR,
                    pagKind + " requires '" + req + "'", List.of()));
            } else if (!SIMPLE_OVERRIDE.matcher(v.asText()).matches()) {
                errs.add(new ValidationError(basePath + ".pagination." + req, Severity.ERROR,
                    "'" + req + "' must be a simple identifier (letters, digits, _ or -); it is "
                        + "interpolated into a JSONPath. Got: " + v.asText(), List.of()));
            }
        }
        ObjectNode expanded = endpoint.deepCopy();
        expanded.set(PAGINATION, presets.expand(pagNode));
        return expanded;
    }

    /**
     * The per-endpoint refusal catalogue. The two kind flags are the only shared context; every rule
     * below is otherwise independent, so this method names them in order rather than holding them.
     * (S3776: the complexity was a LIST of checks, never a knot.)
     */
    private void validateEndpoint(JsonNode ep, String basePath, Set<String> seen, List<ValidationError> errs) {
        JsonNode endpoint = expandPresetIfAny(ep, basePath, errs);
        boolean isSoap = "soap".equals(endpoint.path("kind").asText());
        boolean isGraphql = !isSoap
            && ("graphql".equals(endpoint.path("kind").asText())
                || !absent(endpoint.path("query")));

        errs.addAll(incrementalIsHonest(endpoint, basePath));
        errs.addAll(ConfigDefRunner.run(endpointDefFor(isSoap, isGraphql), endpoint, basePath));
        errs.addAll(endpointIdIsUnique(endpoint, basePath, seen));
        errs.addAll(cursorBindingMatchesKind(endpoint, basePath, isGraphql));
        errs.addAll(soapPairsWithSoapPagination(endpoint, basePath, isSoap));
        errs.addAll(headersStayAtConnectorLevel(endpoint, basePath));
        validatePagination(endpoint.path(PAGINATION), basePath + ".pagination", errs);
        validatePromote(endpoint.path("target").path("promote"), basePath + ".target.promote", errs);
    }

    /**
     * C4: the runtime silently degraded an unknown incremental.mode to a full re-fetch, and a
     * watermark without param silently never applied the watermark — refuse both.
     */
    private static List<ValidationError> incrementalIsHonest(JsonNode endpoint, String basePath) {
        JsonNode inc = endpoint.path("incremental");
        List<ValidationError> errs = new ArrayList<>();
        if (!inc.isMissingNode() && !inc.isNull() && !inc.isObject()) {
            // The runtime reads this as a block — a scalar that slipped past validate (a config
            // written straight to the DB, bypassing apply) is refused loudly by
            // RestEndpointReader.mapConfig rather than degrading to defaults.
            errs.add(new ValidationError(basePath + ".incremental", Severity.ERROR,
                "incremental must be a block ({mode, param, watermark_path}), not a scalar",
                List.of("Write incremental: { mode: watermark, param: ..., watermark_path: ... }")));
        }
        if (!inc.isObject()) return errs;

        String mode = inc.path("mode").asText("full");
        if ("api_cursor".equals(mode)) {
            // Documented as deferred: the runtime treats it as full — warn, keep valid.
            errs.add(new ValidationError(basePath + ".incremental.mode", Severity.WARNING,
                "api_cursor is deferred — the runtime treats it as a full re-fetch",
                List.of("Use mode: watermark for true incrementality")));
        } else if (!"full".equals(mode) && !"watermark".equals(mode)) {
            errs.add(new ValidationError(basePath + ".incremental.mode", Severity.ERROR,
                "unknown incremental mode '" + mode + "' — allowed: full | watermark | api_cursor "
                    + "(an unknown mode used to silently fall back to full)",
                List.of("Use mode: watermark with param + watermark_path, or drop the block")));
        } else if ("watermark".equals(mode)) {
            if (!inc.path("param").isTextual() || inc.path("param").asText().isBlank()) {
                errs.add(new ValidationError(basePath + ".incremental.param", Severity.ERROR,
                    "watermark mode requires param — without it the watermark is never applied",
                    List.of("Set incremental.param: the query parameter carrying the watermark")));
            }
            if (!inc.path("watermark_path").isTextual() || inc.path("watermark_path").asText().isBlank()) {
                errs.add(new ValidationError(basePath + ".incremental.watermark_path", Severity.ERROR,
                    "watermark mode requires watermark_path — without it the watermark never "
                        + "advances and every run re-fetches everything",
                    List.of("Set incremental.watermark_path: JSONPath of the max watermark in the response")));
            }
        }
        return errs;
    }

    private static ConfigDef endpointDefFor(boolean isSoap, boolean isGraphql) {
        if (isSoap) return EndpointsBlockValidator.perSoapEndpointDef();
        if (isGraphql) return EndpointsBlockValidator.perGraphQlEndpointDef();
        return EndpointsBlockValidator.perEndpointDef();
    }

    private static List<ValidationError> endpointIdIsUnique(JsonNode endpoint, String basePath, Set<String> seen) {
        String id = endpoint.path("id").asText();
        if (id.isEmpty() || seen.add(id)) return List.of();
        return List.of(new ValidationError(basePath + ".id", Severity.ERROR,
            "duplicate endpoint id: " + id, List.of()));
    }

    /**
     * E1: GraphQL endpoints must paginate via relay with the cursor bound into body variables. The
     * POST body is built only by RelayPaginationStrategy; any other pagination kind would send a
     * bodyless GET while the runtime still treats the response as GraphQL.
     *
     * <p>The converse too: a cursor written into a POST body only reaches the server from a graphql
     * endpoint. Elsewhere the request stays a GET (body dropped), so the cursor never arrives and the
     * relay loop refetches page 1 up to MAX_PAGES.
     */
    private static List<ValidationError> cursorBindingMatchesKind(JsonNode endpoint, String basePath, boolean isGraphql) {
        JsonNode pag = endpoint.path(PAGINATION);
        if (isGraphql) {
            boolean relayVariable = RELAY.equals(pag.path("kind").asText())
                && VARIABLE.equals(pag.path(CURSOR_BINDING).asText());
            if (relayVariable) return List.of();
            return List.of(new ValidationError(basePath + ".pagination", Severity.ERROR,
                "graphql endpoints require pagination.kind=relay with cursor_binding=variable",
                List.of("Use pagination: { kind: relay, cursor_binding: variable, cursor_var: <name>, "
                    + "cursor_path: ..., has_more_path: ..., response_path: ... }")));
        }
        if (!VARIABLE.equals(pag.path(CURSOR_BINDING).asText())) return List.of();
        return List.of(new ValidationError(basePath + ".pagination.cursor_binding", Severity.ERROR,
            "cursor_binding=variable is only valid for graphql endpoints; "
                + "non-graphql endpoints use cursor_binding=query|header", List.of()));
    }

    /**
     * SOAP request building (SoapPaginationStrategy) and response parsing (XmlBodyParser +
     * SoapFaultValidator) are each selected by their own kind flag. A mismatch would silently pair
     * the wrong request builder with the wrong parser.
     */
    private static List<ValidationError> soapPairsWithSoapPagination(JsonNode endpoint, String basePath, boolean isSoap) {
        String pagKind = endpoint.path(PAGINATION).path("kind").asText();
        if (isSoap && !"soap".equals(pagKind)) {
            return List.of(new ValidationError(basePath + ".pagination.kind", Severity.ERROR,
                "kind=soap endpoints require pagination.kind=soap",
                List.of("Use: pagination: { kind: soap, response_path: ... }")));
        }
        if (!isSoap && "soap".equals(pagKind)) {
            return List.of(new ValidationError(basePath + ".pagination.kind", Severity.ERROR,
                "pagination.kind=soap requires endpoints[].kind=soap",
                List.of("Set kind: soap on the endpoint")));
        }
        return List.of();
    }

    /**
     * E1 ships connector-level headers only. The engine shallow-merges endpoint config over the
     * connector (ChunkExecutor), so an endpoint-level headers key would silently override them.
     */
    private static List<ValidationError> headersStayAtConnectorLevel(JsonNode endpoint, String basePath) {
        // absent: a `headers:` written empty declares no per-endpoint header, so there is nothing
        // to move up to spec.headers and nothing to refuse (#552).
        if (absent(endpoint.path("headers"))) return List.of();
        return List.of(new ValidationError(basePath + ".headers", Severity.ERROR,
            "per-endpoint headers are not supported in this release; declare headers at spec.headers",
            List.of("Move the headers block up to spec.headers (connector level)")));
    }

    /** Per-kind required pagination keys — exactly what each strategy reads to extract items + advance. */
    private void validatePagination(JsonNode pag, String p, List<ValidationError> errs) {
        switch (pag.path("kind").asText()) {
            case "page"            -> requireKeys(pag, p, errs, "page_param", RESPONSE_PATH);
            case "offset"          -> requireKeys(pag, p, errs, "offset_param", "limit_param", RESPONSE_PATH);
            // has_more_path is required: next() stops after page 1 when it's absent (silent partial ingestion).
            case "cursor", RELAY -> {
                requireKeys(pag, p, errs, "cursor_path", "has_more_path", RESPONSE_PATH);
                // GraphQL relay carries the cursor in a body variable (cursor_var); REST in a param.
                if (VARIABLE.equals(pag.path(CURSOR_BINDING).asText())) {
                    // Only RelayPaginationStrategy reads the cursor from a body variable, whereas
                    // CursorPaginationStrategy rejects cursor_binding=variable with IllegalArgumentException.
                    if (!RELAY.equals(pag.path("kind").asText())) {
                        errs.add(new ValidationError(p + ".cursor_binding", Severity.ERROR,
                            "cursor_binding=variable is only valid for pagination.kind=relay; "
                                + "cursor pagination uses cursor_binding=query|header", List.of()));
                    }
                    requireKeys(pag, p, errs, "cursor_var");
                } else {
                    requireKeys(pag, p, errs, "cursor_param");
                }
            }
            // link-header still reads response_path in items() — without it, it defaults to "$" and
            // reads the wrong shape, silently.
            case "link-header" -> requireKeys(pag, p, errs, RESPONSE_PATH);
            // custom has no runtime: it would pass here and fail the run on page 2 (#1245).
            case "custom" -> errs.add(new ValidationError(p + ".kind", Severity.ERROR,
                CustomPaginationStrategy.NOT_SUPPORTED, List.of()));
            case "body-link" -> requireKeys(pag, p, errs, "link_path", RESPONSE_PATH);
            // SOAP advances by offset arithmetic; page_size and response_path are required.
            // page_size must be explicit — the default of 100 may silently mismatch the service's
            // actual page size, causing skipped or duplicated rows when the body template uses
            // {{page_size}}. last_page_path / has_more_path are optional (short-page stop otherwise).
            // response_path must be a plain dotted path — JsonPath operators ([*], ..) bypass the
            // XmlBodyParser force-list and silently turn single elements into objects (data loss).
            case "soap" -> {
                requireKeys(pag, p, errs, RESPONSE_PATH, "page_size");
                String rp = pag.path(RESPONSE_PATH).asText();
                // Same predicate the runtime force-list (XmlBodyParser) walks — shared so the
                // front-door rejection and the runtime walk can never drift (validator==runtime).
                if (!rp.isBlank() && !DottedPath.isPlain(rp)) {
                    errs.add(new ValidationError(p + "." + RESPONSE_PATH, Severity.ERROR,
                        "SOAP response_path must be a plain dotted path (e.g. $.Body.Out.List.Item) — "
                            + "JsonPath operators ([*], ..) bypass the force-list and cause silent single-element data loss",
                        List.of("Remove operators; use a plain dotted path like $.Body.Out.ListOfItems.Item")));
                }
            }
            default                -> { /* unset: no extra required keys */ }
        }
    }

    private void requireKeys(JsonNode node, String p, List<ValidationError> errs, String... keys) {
        String kind = node.path("kind").asText();
        for (String k : keys) {
            if (blankOrNull(node.path(k))) {
                errs.add(new ValidationError(p + "." + k, Severity.ERROR,
                    k + " is required when pagination.kind = " + kind, List.of()));
            }
        }
    }

    private static final Set<String> PROMOTE_TYPES =
        Set.of("text", "bigint", "integer", "decimal", "boolean", "date", "timestamptz", "jsonb");

    /** promote[] element shape (mirrors TargetBlockValidator.validateIndexes): each entry needs a
     *  non-blank name + path + a known type, or the ext.* DDL / extraction breaks at runtime. */
    private void validatePromote(JsonNode promote, String p, List<ValidationError> errs) {
        if (promote.isMissingNode() || promote.isNull()) return;
        if (!promote.isArray()) {
            errs.add(new ValidationError(p, Severity.ERROR,
                "promote must be a list of { name, path, type } objects", List.of()));
            return;
        }
        for (int i = 0; i < promote.size(); i++) {
            validatePromoteEntry(promote.get(i), p + "[" + i + "]", errs);
        }
    }

    private void validatePromoteEntry(JsonNode e, String p, List<ValidationError> errs) {
        if (!e.isObject()) {
            errs.add(new ValidationError(p, Severity.ERROR,
                "promote entry must be an object with name/path/type", List.of()));
            return;
        }
        for (String f : List.of("name", "path", "type")) {
            if (blankOrNull(e.path(f))) {
                errs.add(new ValidationError(p + "." + f, Severity.ERROR,
                    f + " is required and must be non-blank", List.of()));
            }
        }
        String type = e.path("type").asText();
        if (!type.isBlank() && !PROMOTE_TYPES.contains(type.toLowerCase())) {
            errs.add(new ValidationError(p + ".type", Severity.ERROR,
                "unknown promote type '" + type + "' (allowed: " + new TreeSet<>(PROMOTE_TYPES) + ")",
                List.of()));
        }
        // validator == runtime, both ways: the writer's `transformRaw != null` guard treats an
        // explicit null as absent — but a blank/non-text value reaches Transform.valueOf and
        // crashes the run, and so does an unknown name.
        JsonNode transformNode = e.path("transform");
        if (!transformNode.isMissingNode() && !transformNode.isNull()) {
            String transform = transformNode.isTextual() ? transformNode.asText() : "";
            String allowed = Arrays.stream(HybridJsonbSpec.Transform.values())
                .map(t -> t.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(", "));
            if (transform.isBlank()) {
                errs.add(new ValidationError(p + ".transform", Severity.ERROR,
                    "transform must be a non-blank string (allowed: " + allowed + ")", List.of()));
                return;
            }
            try {
                HybridJsonbSpec.Transform.valueOf(transform.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                errs.add(new ValidationError(p + ".transform", Severity.ERROR,
                    "unknown transform '" + transform + "' (allowed: " + allowed + ")", List.of()));
            }
        }
    }

    /** Connector-level request headers: each entry needs a name + exactly one of value / value_env. */
    private void validateHeaders(JsonNode headers, List<ValidationError> errs) {
        if (headers.isMissingNode() || headers.isNull()) return;
        // The SHAPE belongs to the declaration now: `optional("headers", Type.LIST, …)` makes the
        // runner refuse a non-list, and repeating the check here would report one mistake twice —
        // the very duplication removed from validateAuth above. What stays is the per-entry rule
        // no single-key declaration can express. The expected shape lives in the doc string, which
        // `lm describe` serves.
        if (!headers.isArray()) return;
        for (int i = 0; i < headers.size(); i++) {
            JsonNode h = headers.get(i);
            String p = "spec.headers[" + i + "]";
            if (blankOrNull(h.path("name"))) {
                errs.add(new ValidationError(p + ".name", Severity.ERROR, "name is required", List.of()));
            }
            // "Usable" presence must mirror RequestHeaders.apply: an explicit null value or a blank
            // value_env passes a naive isMissingNode() check but is silently skipped at runtime.
            boolean hasValue = h.hasNonNull("value");
            boolean hasEnv = h.hasNonNull("value_env") && !h.path("value_env").asText().isBlank();
            if (hasValue == hasEnv) {
                errs.add(new ValidationError(p, Severity.ERROR,
                    "exactly one of value / value_env is required (value non-null; value_env non-blank)",
                    List.of()));
            }
        }
    }
}
