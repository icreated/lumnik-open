package io.lumnik.hub.validator.rest;

import io.lumnik.hub.connector.rest.soap.SoapRequestBuilder;
import io.lumnik.hub.validator.ConfigDef;
import io.lumnik.hub.validator.ConfigDef.Importance;
import io.lumnik.hub.validator.ConfigDef.Type;
import io.lumnik.hub.validator.ConfigDefValidators;
import io.lumnik.hub.validator.common.NoMappingBlock;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class EndpointsBlockValidator {
    private EndpointsBlockValidator() {}

    private static final String ID_DESCRIPTION       = "Unique endpoint identifier (kebab-case)";
    private static final String ID_REGEX             = "^[a-z][a-z0-9-]*$";
    private static final String ID_REGEX_DESCRIPTION = "kebab-case identifier";
    private static final String PAGINATION_KEY       = "pagination";
    private static final String PAGINATION_DESC      = "Pagination strategy for this endpoint";
    private static final String TARGET_KEY           = "target";
    private static final String TARGET_DESC          = "Hub persistence target for this endpoint";
    /** One advice for headers on an endpoint, whatever their shape: the type refusal and the list refusal. */
    static final String HEADERS_ADVICE               = "Move the headers block up to spec.headers (connector level)";

    public static ConfigDef configDef() {
        return new ConfigDef()
            .require("endpoints", Type.LIST, Importance.HIGH, "Array of endpoint definitions");
    }

    /**
     * Per-endpoint contract enforced by the runtime (proven by the engine ITs): {@code id}, {@code path},
     * {@code method}, a {@code pagination} block (kind + kind-specific keys, cross-checked in
     * {@link RestConnectorValidator}) and a {@code target} block (hybrid: table + unique_key + promote).
     * {@code incremental} is optional (defaults to full).
     */
    public static ConfigDef perEndpointDef() {
        return closedWithSharedKeys(new ConfigDef()
            .requireCustom("id", Type.STRING, Importance.HIGH,
                ID_DESCRIPTION,
                ConfigDefValidators.matchesRegex(ID_REGEX, ID_REGEX_DESCRIPTION))
            .require("path", Type.STRING, Importance.HIGH,
                "URL path appended to base_url, e.g. /v1/customers")
            .requireEnum("method", Importance.MEDIUM, "HTTP method",
                "GET", "POST", "PUT", "PATCH", "DELETE")
            .requireNested(PAGINATION_KEY, Importance.HIGH,
                PAGINATION_DESC, paginationDef())
            .requireNested(TARGET_KEY, Importance.HIGH,
                TARGET_DESC, targetDef()));
    }

    /**
     * GraphQL endpoints hit a single POST endpoint ({@code graphql_path}) with the query in the
     * body, so {@code path}/{@code method} are implicit and not required; {@code query} is.
     */
    public static ConfigDef perGraphQlEndpointDef() {
        return closedWithSharedKeys(new ConfigDef()
            .requireCustom("id", Type.STRING, Importance.HIGH,
                ID_DESCRIPTION,
                ConfigDefValidators.matchesRegex(ID_REGEX, ID_REGEX_DESCRIPTION))
            .requireCustom("query", Type.STRING, Importance.HIGH,
                "The GraphQL query document (with $cursor variable for relay pagination)",
                ConfigDefValidators.nonEmpty())
            .optional("variables", Type.NESTED, Importance.LOW,
                "Static variables sent with every request; the relay cursor is injected on top", null)
            .requireNested(PAGINATION_KEY, Importance.HIGH,
                PAGINATION_DESC, paginationDef())
            .requireNested(TARGET_KEY, Importance.HIGH,
                TARGET_DESC, targetDef()));
    }

    /**
     * SOAP endpoints POST a rendered XML envelope to {@code base_url + path}, so {@code body} (the
     * template) is required; {@code header} and {@code soap_action} are optional; {@code method} is
     * implicit (POST). {@code path}, {@code pagination}, {@code target} as for REST.
     */
    public static ConfigDef perSoapEndpointDef() {
        return closedWithSharedKeys(new ConfigDef()
            .requireCustom("id", Type.STRING, Importance.HIGH,
                ID_DESCRIPTION,
                ConfigDefValidators.matchesRegex(ID_REGEX, ID_REGEX_DESCRIPTION))
            .require("path", Type.STRING, Importance.HIGH,
                "SOAP service path appended to base_url, e.g. /eai_enu/start.swe")
            .requireCustom("body", Type.STRING, Importance.HIGH,
                "The SOAP body template (inner <Body> content; supports " + SoapRequestBuilder.PLACEHOLDERS + ")",
                ConfigDefValidators.nonEmpty())
            .optional("header", Type.STRING, Importance.LOW,
                "The SOAP header template (inner <Header> content; same placeholders as body)", null)
            .optional("soap_action", Type.STRING, Importance.LOW,
                "Sent as the SOAPAction header (an empty quoted string when absent)", null)
            .requireNested(PAGINATION_KEY, Importance.HIGH,
                PAGINATION_DESC, paginationDef())
            .requireNested(TARGET_KEY, Importance.HIGH,
                TARGET_DESC, targetDef()));
    }

    /**
     * {@code spec.defaults}: the run lays it under every endpoint, whatever its request shape, so its key set is the
     * three shapes' together — none required, each endpoint carries its own — and closed (#1461). Minus the keys the
     * run never reads there, so the closed sweep names them (#1471); {@link #SHAPE_KEYS} and
     * {@link #CONNECTOR_LEVEL_KEYS} stay declared, refused by {@link RestConnectorValidator}.
     */
    public static ConfigDef defaultsDef() {
        ConfigDef defaults = new ConfigDef();
        // Declared first so optionalAll keeps these docs: the shapes' own say the key is valid there.
        for (String key : SHAPE_KEYS) {
            defaults.optional(key, Type.STRING, Importance.LOW,
                "Not accepted in spec.defaults — refused by validation; declare it on each endpoint", null);
        }
        for (String key : CONNECTOR_LEVEL_KEYS) {
            defaults.optional(key, "auth".equals(key) ? Type.NESTED : Type.STRING, Importance.LOW,
                "Not accepted in spec.defaults — refused by validation; declare it at spec." + key, null);
        }
        return defaults
            .optionalAll(perEndpointDef())
            .optionalAll(perGraphQlEndpointDef())
            .optionalAll(perSoapEndpointDef())
            .without(NEVER_READ_FROM_DEFAULTS)
            .closed();
    }

    /**
     * Keys every endpoint carries itself, so a default of them is never read: {@code id}, {@code method} and
     * {@code path} come from the endpoint's own node, and the endpoint's {@code pagination}, {@code target} and SOAP
     * {@code body} (each required on its shape) replace the defaults' whole.
     */
    private static final List<String> NEVER_READ_FROM_DEFAULTS = List.of("id", "method", "path", PAGINATION_KEY, TARGET_KEY, "body");

    /**
     * What an endpoint inherits from {@code spec.defaults} (#1481): the keys the run reads off the merged config that
     * the endpoint may leave out. Each endpoint is validated merged with the ones its request shape reads; every other
     * key {@link #defaultsDef} declares is refused there or never read.
     */
    public static final List<String> INHERITED_FROM_DEFAULTS = List.of("incremental", "variables", "header", "soap_action");

    /**
     * The keys the run decides an endpoint's request shape from, on its merged config ({@code RestEndpointReader}'s
     * GraphQL and SOAP predicates): in {@code spec.defaults} they would switch every endpoint's shape (#1471).
     */
    public static final List<String> SHAPE_KEYS = List.of("kind", "query");

    /**
     * The connector-level keys an endpoint may carry and the run still reads: the endpoint's config is laid over the
     * connector's, so the run looks each one up here first. Declared below so the closed sweep does not call them
     * ignored; {@link RestConnectorValidator} sends them back up to spec, where they are checked.
     */
    public static final List<String> CONNECTOR_LEVEL_KEYS = List.of("base_url", "auth", "default_rate_limit", "graphql_path");

    /**
     * Every key an endpoint of any request shape reads — the keys a {@code spec.defaults} passthrough could have stored.
     * Below {@link #CONNECTOR_LEVEL_KEYS}: the shapes read it, and a static field is initialised in declaration order.
     */
    public static final Set<String> ENDPOINT_KEYS = Stream.of(perEndpointDef(), perGraphQlEndpointDef(), perSoapEndpointDef())
        .flatMap(shape -> shape.fields().stream())
        .map(ConfigDef.FieldSpec::name)
        .collect(Collectors.toUnmodifiableSet());

    /**
     * What every request shape reads besides its own keys, then the shape closed: an undeclared key is named instead
     * of dropped (#1438). {@code headers} and {@code mapping} are declared to be refused, so a refusal is not also
     * an unknown-key warning.
     */
    private static ConfigDef closedWithSharedKeys(ConfigDef shape) {
        for (String key : CONNECTOR_LEVEL_KEYS) {
            shape.optional(key, "auth".equals(key) ? Type.NESTED : Type.STRING, Importance.LOW,
                "Connector-level — declare it at spec." + key, null);
        }
        return shape
            .optional("kind", Type.STRING, Importance.MEDIUM,
                "Request shape: soap or graphql (an endpoint carrying a query is GraphQL either way); omit for REST", null)
            .optionalHinted("incremental", Type.NESTED, Importance.MEDIUM,
                "Incremental strategy (mode full | watermark; defaults to full)", null,
                "incremental is a block, not a scalar: incremental: { mode: watermark, param: ..., watermark_path: ... }")
            .optionalHinted("headers", Type.LIST, Importance.LOW,
                "Not accepted here — refused by validation; declare headers at spec.headers", null, HEADERS_ADVICE)
            .optional("mapping", Type.NESTED, Importance.LOW, NoMappingBlock.DOC, null)
            .closed();
    }

    /** Pagination block: {@code kind} drives which keys the runtime reads (cross-checked per kind). */
    static ConfigDef paginationDef() {
        return new ConfigDef()
            .requireEnum("kind", Importance.HIGH, "Pagination strategy",
                "offset", "page", "cursor", "link-header", "relay", "body-link", "custom", "soap");
    }

    /** Hybrid target block: a hub table keyed by unique_key, with optional promoted columns. */
    static ConfigDef targetDef() {
        return new ConfigDef()
            .optional("kind", Type.STRING, Importance.LOW,
                "Target kind (hybrid persists a hub table + RAG document)", "hybrid")
            .requireCustom("table", Type.STRING, Importance.HIGH,
                "Hub table name (under ext.*) this endpoint writes to",
                ConfigDefValidators.plainQualifiedIdentifier())
            .require("unique_key", Type.STRING, Importance.HIGH,
                "JSONPath to the natural key used for upsert, e.g. $.id")
            .optional("promote", Type.LIST, Importance.MEDIUM,
                "Columns promoted from the JSON payload to typed hub columns", null);
    }
}
