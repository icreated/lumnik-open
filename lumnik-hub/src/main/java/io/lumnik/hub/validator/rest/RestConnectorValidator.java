package io.lumnik.hub.validator.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lumnik.hub.connector.rest.auth.SecretRedactor;
import io.lumnik.hub.connector.rest.reliability.RateLimiterRegistry;
import io.lumnik.hub.connector.registry.ConnectorTypeId;
import io.lumnik.hub.connector.rest.DottedPath;
import io.lumnik.hub.connector.rest.pagination.CustomPaginationStrategy;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec;
import io.lumnik.hub.connector.rest.pagination.preset.PaginationPresetRegistry;
import io.lumnik.hub.connector.rest.soap.SoapRequestBuilder;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.rest.runtime.RestEndpointReader;
import io.lumnik.hub.validator.*;
import io.lumnik.hub.validator.ConfigDef.Importance;
import static io.lumnik.hub.validator.JsonNodes.absent;
import static io.lumnik.hub.validator.JsonNodes.blankOrNull;
import io.lumnik.hub.validator.ConfigDef.Type;
import io.lumnik.hub.validator.ValidationError.Severity;
import io.lumnik.hub.validator.common.CronField;
import io.lumnik.hub.validator.common.MappingBlockValidator;
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
    @Inject MappingBlockValidator mappings;

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
            // #1609: the source's HTTPS is signed by an internal CA (a cluster's API server, an in-house PKI).
            .optionalNested("tls", Importance.MEDIUM,
                "Trust an internal CA for every endpoint: {ca_secret: the lm secret holding its PEM certificate(s)}",
                new ConfigDef()
                    // Blank would read as "no CA" — refused here, and failed closed at runtime (CaTrust).
                    .requireCustom("ca_secret", Type.STRING, Importance.HIGH,
                        "Name of the lm secret holding the CA's PEM certificate(s); only those CAs are trusted",
                        nonEmpty())
                    .closed())
            .optionalHinted("headers", Type.LIST, Importance.MEDIUM,
                "Static headers sent on every request, [{name, value | value_env}]", null,
                "headers is a list of { name, value|value_env } objects")
            .optionalNested("defaults", Importance.LOW,
                "Per-endpoint defaults merged into each endpoint", EndpointsBlockValidator.defaultsDef())
            .optional("graphql_path", Type.STRING, Importance.LOW,
                "Path appended to base_url for GraphQL endpoints (default /graphql)", null)
            // schedule's twin, read by the SHARED apply preamble (ApplyUpsert); declared and
            // checked through WindowField, which owns both wordings for all four connector types.
            .optional("window", Type.STRING, Importance.MEDIUM, WindowField.DOC, null)
            // Declared to be refused: a mapping runs over one endpoint's promoted columns (#1437, #1617).
            .optional("mapping", Type.NESTED, Importance.LOW, ON_THE_ENDPOINT, null)
            .closed();
    }

    @Override
    public List<ValidationError> validateCrossField(JsonNode spec) {
        List<ValidationError> errs = new ArrayList<>();
        mappingOnTheEndpoint(spec.path("mapping"), "spec.mapping", errs);
        validateAuth(spec.path("auth"), errs);
        validateEndpoints(spec.path("endpoints"), spec.path("defaults"), errs);
        anyShapeRefusals(spec.path("defaults"), "spec.defaults", errs);
        mappingOnTheEndpoint(spec.path("defaults").path("mapping"), "spec.defaults.mapping", errs);
        errs.addAll(defaultsCannotOverride(spec.path("defaults")));
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

    private static final List<String> OAUTH2_KEYS = List.of("token_url", "client_id", "client_secret_env", "scope", "client_auth");

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
        // The oauth2 keys are declared for every kind (the block is one closed key set), so under another kind they
        // validated silently: a manifest whose kind says one thing and whose keys another (Copilot on #1394).
        String kind = auth.path("kind").asText();
        // Checked under oauth2 only: elsewhere the runtime ignores the key, and the warning below says so.
        JsonNode clientAuth = auth.path("client_auth");
        if ("oauth2".equals(kind) && !clientAuth.isMissingNode()
                && !"basic".equals(clientAuth.asText()) && !"post".equals(clientAuth.asText())) {
            errs.add(new ValidationError("spec.auth.client_auth", Severity.ERROR, "client_auth must be basic or post",
                List.of("basic (the default) sends the client id and secret as HTTP Basic; post puts them in the body")));
        }
        // scheme is bearer's alone (#1640): one word before the token, as the header's grammar has it.
        JsonNode scheme = auth.path("scheme");
        if (!scheme.isMissingNode()) {
            if (!"bearer".equals(kind)) {
                errs.add(new ValidationError("spec.auth.scheme", Severity.WARNING,
                    "scheme under kind: " + kind + " — the runtime ignores it",
                    List.of("Remove it: scheme names the word before a bearer token (Authorization: Token <key>)")));
            } else if (scheme.isTextual() && !(SecretRedactor.isSchemeWord(scheme.asText()) && scheme.asText().length() <= 32)) {
                // A non-string is the declaration's refusal (Type.STRING); null is absent — Bearer at runtime.
                errs.add(new ValidationError("spec.auth.scheme", Severity.ERROR,
                    "scheme must be one word starting with a letter — letters, digits, '-' or '_' — at most 32",
                    List.of("Bearer (the default), or the word the API expects, e.g. Token for Django REST Framework")));
            }
        }
        if (!"oauth2".equals(kind)) {
            for (String key : OAUTH2_KEYS) {
                if (auth.has(key)) {
                    errs.add(new ValidationError("spec.auth." + key, Severity.WARNING,
                        "an oauth2 key under kind: " + kind + " — the runtime ignores it",
                        List.of("Remove it, or set kind: oauth2 if this source hands out OAuth2 tokens")));
                }
            }
        }
        if ("bearer".equals(auth.path("kind").asText())
                && blankOrNull(auth.path("token_env"))
                && blankOrNull(auth.path("token_value"))) {
            errs.add(new ValidationError("spec.auth.token_env", Severity.ERROR,
                "bearer auth requires token_env (or inline token_value)",
                List.of("Use `lm secret set <NAME> ...` then reference via token_env: <NAME>")));
        }
    }

    /**
     * Each endpoint as the run executes it: laid over the defaults it inherits (#1481). An error on an inherited key is
     * named where the integrator wrote it, {@code spec.defaults.<key>} — once, since the service drops repeats.
     */
    private void validateEndpoints(JsonNode endpoints, JsonNode defaults, List<ValidationError> errs) {
        if (!endpoints.isArray()) return;
        if (endpoints.isEmpty()) {
            errs.add(new ValidationError("spec.endpoints", Severity.ERROR,
                "at least one endpoint is required",
                List.of("Add an endpoint: { id: 'customers', path: '/v1/customers', method: 'GET' }")));
            return;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < endpoints.size(); i++) {
            String basePath = "spec.endpoints[" + i + "]";
            JsonNode endpoint = endpoints.get(i);
            List<String> inherited = inheritedKeys(endpoint, defaults);
            List<ValidationError> own = new ArrayList<>();
            validateEndpoint(withInherited(endpoint, defaults, inherited), basePath, seen, own);
            own.forEach(e -> errs.add(atWrittenPath(e, basePath, inherited)));
        }
    }

    /** The defaults this endpoint runs with: the ones its request shape reads and it leaves out itself. */
    private static List<String> inheritedKeys(JsonNode endpoint, JsonNode defaults) {
        if (!endpoint.isObject() || !defaults.isObject()) return List.of();
        ConfigDef shape = endpointDefFor(isSoap(endpoint), isGraphql(endpoint));
        return EndpointsBlockValidator.INHERITED_FROM_DEFAULTS.stream()
            .filter(key -> shape.declares(key) && absent(endpoint.path(key)) && !absent(defaults.path(key)))
            .toList();
    }

    private static JsonNode withInherited(JsonNode endpoint, JsonNode defaults, List<String> inherited) {
        if (inherited.isEmpty()) return endpoint;
        ObjectNode merged = endpoint.deepCopy();
        inherited.forEach(key -> merged.set(key, defaults.get(key)));
        return merged;
    }

    private static ValidationError atWrittenPath(ValidationError e, String basePath, List<String> inherited) {
        for (String key : inherited) {
            String at = basePath + "." + key;
            if (!e.path().startsWith(at)) continue;
            String rest = e.path().substring(at.length());
            if (rest.isEmpty() || rest.startsWith(".") || rest.startsWith("[")) {
                return new ValidationError("spec.defaults." + key + rest, e.severity(), e.message(), e.recommendations());
            }
        }
        return e;
    }

    /**
     * The per-endpoint refusals that hold whatever the request shape: on each endpoint, and on spec.defaults, which the
     * run lays under every endpoint (#1461) — one list, so a refusal added here reaches both tiers. A mapping is the
     * exception: allowed on an endpoint, refused in the defaults ({@link #mappingOnTheEndpoint}).
     */
    private static void anyShapeRefusals(JsonNode block, String basePath, List<ValidationError> errs) {
        errs.addAll(headersStayAtConnectorLevel(block, basePath));
    }

    /** What {@link #configDef} says of {@code spec.mapping}, and {@link EndpointsBlockValidator} of the defaults'. */
    static final String ON_THE_ENDPOINT = "Not accepted here — refused by validation; declare the mapping on its endpoint";

    /**
     * #1617: a mapping runs over one endpoint's promoted columns — spec.mapping and spec.defaults.mapping would run over
     * none (in the defaults, over columns each endpoint declares differently). Refused when a block, as the declaration
     * refuses any other shape.
     */
    private static void mappingOnTheEndpoint(JsonNode mapping, String at, List<ValidationError> errs) {
        if (!mapping.isObject()) return;
        errs.add(new ValidationError(at, Severity.ERROR,
            "a mapping runs over one endpoint's promoted columns — declared here, it would run over none",
            List.of("Declare it on the endpoint: endpoints[].mapping, beside the target whose promote it reads")));
    }

    /**
     * #1617: the csv checks of a mapping, at the endpoint's path, over the columns its promote entries read; plus the
     * hooks that cannot run on items mapped one by one, each row landing beside its own: split-row and aggregator make
     * rows of several or several of one, sort-by orders a batch.
     */
    private void validateMapping(JsonNode endpoint, String basePath, List<ValidationError> errs) {
        JsonNode mapping = endpoint.path("mapping");
        if (!mapping.isObject()) return;
        String at = basePath + ".mapping";
        errs.addAll(mappings.validate(mapping, at));
        errs.addAll(MappingBlockValidator.hookRefused(mapping, at, "cell", "fill-down", "fill-down carries a value from "
            + "one row to the next, and a REST endpoint maps each item on its own — each item holds its own values",
            "Promote the value from the item itself; drop the fill-down hook"));
        for (String hook : List.of("split-row", "aggregator")) {
            errs.addAll(MappingBlockValidator.hookRefused(mapping, at, "row", hook, hook + " makes rows of several, or "
                + "several of one — each row of a REST endpoint lands beside the item it was read from",
                "Shape each item's own columns; drop the " + hook + " hook"));
        }
        errs.addAll(MappingBlockValidator.hookRefused(mapping, at, "dataset", "sort-by", "sort-by orders a batch, and "
            + "a REST endpoint maps each item on its own — the order would change nothing",
            "Drop the sort-by hook; order the rows when you read them"));
        Set<String> promoted = new HashSet<>();
        Set<String> declared = new HashSet<>();
        for (JsonNode column : endpoint.path("target").path("promote")) {
            declared.add(column.path("name").asText());
            if (!blankOrNull(column.path("path"))) promoted.add(column.path("name").asText());
        }
        errs.addAll(mappings.columnsThatDoNotExist(mapping, at, promoted, Map.of(), declared));
    }

    /**
     * What an endpoint may carry but spec.defaults may not (#1471). A connector-level key there is refused, not sent up
     * with a warning as on an endpoint: spec's own value wins over it, and without one it would run past every check of
     * spec's — a literal token in defaults.auth escaped the #530 warning. A request-shape key there would switch every
     * endpoint's shape while each endpoint is validated as its own node says.
     */
    private static List<ValidationError> defaultsCannotOverride(JsonNode defaults) {
        List<ValidationError> errs = new ArrayList<>();
        for (String key : EndpointsBlockValidator.CONNECTOR_LEVEL_KEYS) {
            if (absent(defaults.path(key))) continue;
            errs.add(new ValidationError("spec.defaults." + key, Severity.ERROR,
                "a connector-level key in spec.defaults — spec." + key + " wins over it, and only spec." + key + " is checked",
                List.of("Declare it once at spec." + key + ", where it is checked")));
        }
        for (String key : EndpointsBlockValidator.SHAPE_KEYS) {
            if (absent(defaults.path(key))) continue;
            errs.add(new ValidationError("spec.defaults." + key, Severity.ERROR,
                "a request-shape key in spec.defaults — it would switch the shape of every endpoint, each validated as its own",
                List.of("Declare " + key + " on each endpoint of that shape")));
        }
        return errs;
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
        boolean isSoap = isSoap(endpoint);
        boolean isGraphql = isGraphql(endpoint);

        errs.addAll(incrementalIsHonest(endpoint, basePath));
        errs.addAll(graphqlWatermarkVariableIsDeclared(endpoint, basePath, isGraphql));
        errs.addAll(ConfigDefRunner.run(endpointDefFor(isSoap, isGraphql), endpoint, basePath));
        errs.addAll(endpointIdIsUnique(endpoint, basePath, seen));
        errs.addAll(cursorBindingMatchesKind(endpoint, basePath, isGraphql));
        errs.addAll(soapPairsWithSoapPagination(endpoint, basePath, isSoap));
        errs.addAll(soapPlaceholdersAreKnown(endpoint, basePath, isSoap));
        anyShapeRefusals(endpoint, basePath, errs);
        validateMapping(endpoint, basePath, errs);
        errs.addAll(connectorLevelKeysStayAtSpec(endpoint, basePath));
        validatePagination(endpoint.path(PAGINATION), basePath + ".pagination", errs);
        validatePromote(endpoint.path("target").path("promote"), basePath + ".target.promote",
            endpoint.path("mapping").isObject(), errs);
    }

    /**
     * C4: the runtime silently degraded an unknown incremental.mode to a full re-fetch, and a
     * watermark without param silently never applied the watermark — refuse both.
     */
    private static List<ValidationError> incrementalIsHonest(JsonNode endpoint, String basePath) {
        JsonNode inc = endpoint.path("incremental");
        List<ValidationError> errs = new ArrayList<>();
        // A scalar is the declaration's type refusal (EndpointsBlockValidator, #1438); refused here too, one mistake
        // would read twice. One that slipped past validate (a config written straight to the DB, bypassing apply) is
        // refused loudly by RestEndpointReader.mapConfig rather than degrading to defaults.
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
            JsonNode lookback = inc.path("lookback");
            if (!lookback.isMissingNode() && !lookback.isNull()) {
                try {
                    RestEndpointReader.lookback(lookback.asText());
                } catch (ConnectorException notADuration) {
                    errs.add(new ValidationError(basePath + ".incremental.lookback", Severity.ERROR,
                        "lookback must be a number of s, m or h — '" + lookback.asText() + "' is not one",
                        List.of("lookback: 10m — or 0s to send the mark exactly as stored")));
                }
            }
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

    /**
     * #1543: on a graphql endpoint the watermark is sent as the GraphQL variable named by
     * {@code incremental.param}. A server refuses a variable its query does not declare, so the query
     * must carry {@code $<param>:} among its variable definitions.
     */
    private static List<ValidationError> graphqlWatermarkVariableIsDeclared(JsonNode endpoint, String basePath,
                                                                            boolean isGraphql) {
        JsonNode inc = endpoint.path("incremental");
        JsonNode param = inc.path("param");
        JsonNode query = endpoint.path("query");
        if (!isGraphql || !"watermark".equals(inc.path("mode").asText()) || !param.isTextual()
            || param.asText().isBlank() || !query.isTextual()) {
            return List.of();
        }
        String name = param.asText().strip();
        if (Pattern.compile("\\$" + Pattern.quote(name) + "\\s*:").matcher(query.asText()).find()) return List.of();
        return List.of(new ValidationError(basePath + ".incremental.param", Severity.ERROR,
            "on a graphql endpoint the watermark is sent as the variable $" + name
                + ", which the query does not declare",
            List.of("Declare it in the query, nullable: query($after: String, $" + name + ": String) { ... }")));
    }

    private static boolean isSoap(JsonNode endpoint) {
        return "soap".equals(endpoint.path("kind").asText());
    }

    private static boolean isGraphql(JsonNode endpoint) {
        return !isSoap(endpoint)
            && ("graphql".equals(endpoint.path("kind").asText()) || !absent(endpoint.path("query")));
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

    /** The run refuses an unknown {@code {{placeholder}}} before sending anything; apply refuses it first (#1387). */
    private static List<ValidationError> soapPlaceholdersAreKnown(JsonNode endpoint, String basePath, boolean isSoap) {
        List<ValidationError> errs = new ArrayList<>();
        if (!isSoap) return errs;
        for (String key : List.of("header", "body")) {
            for (String p : SoapRequestBuilder.unknownPlaceholders(endpoint.path(key).textValue())) {
                errs.add(new ValidationError(basePath + "." + key, Severity.ERROR, "unknown placeholder: " + p,
                    List.of("Use " + SoapRequestBuilder.PLACEHOLDERS)));
            }
        }
        return errs;
    }

    /**
     * E1 ships connector-level headers only. The engine shallow-merges endpoint config over the
     * connector (ChunkExecutor), so an endpoint-level headers key would silently override them.
     */
    private static List<ValidationError> headersStayAtConnectorLevel(JsonNode endpoint, String basePath) {
        // absent: a `headers:` written empty declares no per-endpoint header, so there is nothing
        // to move up to spec.headers and nothing to refuse (#552). A non-list is the declaration's
        // type refusal, which carries the same advice (#1438).
        if (!endpoint.path("headers").isArray()) return List.of();
        return List.of(new ValidationError(basePath + ".headers", Severity.ERROR,
            "per-endpoint headers are not supported in this release; declare headers at spec.headers",
            List.of(EndpointsBlockValidator.HEADERS_ADVICE)));
    }

    /**
     * A connector-level key written on an endpoint is read there — the endpoint's config is laid over the
     * connector's — but every check of it runs at spec only. Named, and sent back up; a warning, since the run
     * honours it (#1438). In spec.defaults it is refused instead: {@link #defaultsCannotOverride}.
     */
    private static List<ValidationError> connectorLevelKeysStayAtSpec(JsonNode endpoint, String basePath) {
        List<ValidationError> errs = new ArrayList<>();
        for (String key : EndpointsBlockValidator.CONNECTOR_LEVEL_KEYS) {
            if (absent(endpoint.path(key))) continue;
            errs.add(new ValidationError(basePath + "." + key, Severity.WARNING,
                "a connector-level key on an endpoint — the run reads it here, but only spec." + key + " is checked",
                List.of("Declare it once at spec." + key + ", where it is checked")));
        }
        return errs;
    }

    /** Per-kind required pagination keys — exactly what each strategy reads to extract items + advance. */
    private void validatePagination(JsonNode pag, String p, List<ValidationError> errs) {
        String kind = pag.path("kind").asText();
        // One witness of the count: the body's or a header's, never both — they could disagree (#1632). Blank is none, as the run reads it.
        if (!blankOrNull(pag.path("total_path")) && !blankOrNull(pag.path("total_header"))) {
            errs.add(new ValidationError(p + ".total_header", Severity.ERROR,
                "total_header and total_path both name the source's count — declare one",
                List.of("total_header when the API sends the count in a response header (X-Total-Count), total_path when the body carries it")));
        }
        switch (kind) {
            case "page"            -> requireKeys(pag, p, errs, "page_param", RESPONSE_PATH);
            case "offset"          -> requireKeys(pag, p, errs, "offset_param", "limit_param", RESPONSE_PATH);
            // relay requires has_more_path (pageInfo.hasNextPage); cursor may omit it, and then the cursor decides.
            case "cursor", RELAY -> {
                requireKeys(pag, p, errs, "cursor_path", RESPONSE_PATH);
                if (RELAY.equals(kind)) {
                    requireKeys(pag, p, errs, "has_more_path");
                } else if (absent(pag.path("has_more_path"))) {
                    // Valid, and said: a misspelled flag key would otherwise switch modes in silence.
                    errs.add(new ValidationError(p + ".has_more_path", Severity.WARNING,
                        "no has_more_path: the cursor decides — the read stops on an empty cursor or an empty page",
                        List.of("Declare has_more_path if the API sends a more-pages flag")));
                }
                // GraphQL relay carries the cursor in a body variable (cursor_var); REST in a param.
                if (VARIABLE.equals(pag.path(CURSOR_BINDING).asText())) {
                    // Only RelayPaginationStrategy reads the cursor from a body variable, whereas
                    // CursorPaginationStrategy rejects cursor_binding=variable with IllegalArgumentException.
                    if (!RELAY.equals(kind)) {
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
            // none (#1650), the whole list in one answer: only where it is.
            case "link-header", "none" -> requireKeys(pag, p, errs, RESPONSE_PATH);
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
                        "SOAP response_path must be a plain dotted path (e.g. $.Body.Out.List.Item): "
                            + "a path with [*] or .. loses the row of a one-element page",
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
     *  non-blank name + path + a known type, or the ext.* DDL / extraction breaks at runtime. Under a mapping
     *  ({@code mapped}), an entry without a path is a column the mapping fills (#1617). */
    private void validatePromote(JsonNode promote, String p, boolean mapped, List<ValidationError> errs) {
        if (promote.isMissingNode() || promote.isNull()) return;
        if (!promote.isArray()) {
            errs.add(new ValidationError(p, Severity.ERROR,
                "promote must be a list of { name, path, type } objects", List.of()));
            return;
        }
        for (int i = 0; i < promote.size(); i++) {
            validatePromoteEntry(promote.get(i), p + "[" + i + "]", mapped, errs);
        }
    }

    private void validatePromoteEntry(JsonNode e, String p, boolean mapped, List<ValidationError> errs) {
        if (!e.isObject()) {
            errs.add(new ValidationError(p, Severity.ERROR,
                "promote entry must be an object with name/path/type", List.of()));
            return;
        }
        for (String f : List.of("name", "type")) {
            if (blankOrNull(e.path(f))) {
                errs.add(new ValidationError(p + "." + f, Severity.ERROR,
                    f + " is required and must be non-blank", List.of()));
            }
        }
        // Absent or null (`path:` left empty), the column is the mapping's to fill, as the run reads it (#1617);
        // written, it must say where to read.
        JsonNode path = e.path("path");
        boolean none = path.isMissingNode() || path.isNull();
        if (none ? !mapped : blankOrNull(path)) {
            errs.add(new ValidationError(p + ".path", Severity.ERROR, "path is required and must be non-blank",
                none ? List.of("Without a path, the column is filled by the endpoint's mapping — this endpoint has none")
                     : List.of()));
        }
        if (none && mapped && !absent(e.path("transform"))) {
            errs.add(new ValidationError(p + ".transform", Severity.ERROR,
                "transform reads the value at path — a column the mapping fills has none to read",
                List.of("Drop the transform: the mapping writes this column's value")));
        }
        String type = e.path("type").asText();
        if (!type.isBlank() && !PROMOTE_TYPES.contains(type.toLowerCase(Locale.ROOT))) {
            errs.add(new ValidationError(p + ".type", Severity.ERROR,
                "unknown promote type '" + type + "' (allowed: " + new TreeSet<>(PROMOTE_TYPES) + ")",
                List.of()));
        }
        // validator == runtime, both ways: the writer's `transformRaw != null` guard treats an
        // explicit null as absent — but a blank/non-text value reaches Transform.valueOf and
        // crashes the run, and so does an unknown name.
        JsonNode transformNode = e.path("transform");
        if (!transformNode.isMissingNode() && !transformNode.isNull()) {
            transformRefusal(transformNode).ifPresent(m ->
                errs.add(new ValidationError(p + ".transform", Severity.ERROR, m, List.of())));
        }
    }

    /** Why {@code transform} names no promote transform, if it does not — a promote entry's and the key's (#1630). */
    static Optional<String> transformRefusal(JsonNode transformNode) {
        String transform = transformNode.isTextual() ? transformNode.asText() : "";
        String allowed = Arrays.stream(HybridJsonbSpec.Transform.values())
            .map(t -> t.name().toLowerCase(Locale.ROOT))
            .collect(Collectors.joining(", "));
        if (transform.isBlank()) return Optional.of("transform must be a non-blank string (allowed: " + allowed + ")");
        try {
            HybridJsonbSpec.Transform.valueOf(transform.toUpperCase(Locale.ROOT));
            return Optional.empty();
        } catch (IllegalArgumentException ex) {
            return Optional.of("unknown transform '" + transform + "' (allowed: " + allowed + ")");
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
