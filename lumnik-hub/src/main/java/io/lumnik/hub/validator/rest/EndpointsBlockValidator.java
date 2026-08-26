package io.lumnik.hub.validator.rest;

import io.lumnik.hub.validator.ConfigDef;
import io.lumnik.hub.validator.ConfigDef.Importance;
import io.lumnik.hub.validator.ConfigDef.Type;
import io.lumnik.hub.validator.ConfigDefValidators;

public final class EndpointsBlockValidator {
    private EndpointsBlockValidator() {}

    private static final String ID_DESCRIPTION       = "Unique endpoint identifier (kebab-case)";
    private static final String ID_REGEX             = "^[a-z][a-z0-9-]*$";
    private static final String ID_REGEX_DESCRIPTION = "kebab-case identifier";
    private static final String PAGINATION_KEY       = "pagination";
    private static final String PAGINATION_DESC      = "Pagination strategy for this endpoint";
    private static final String TARGET_KEY           = "target";
    private static final String TARGET_DESC          = "Hub persistence target for this endpoint";

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
        return new ConfigDef()
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
                TARGET_DESC, targetDef());
    }

    /**
     * GraphQL endpoints hit a single POST endpoint ({@code graphql_path}) with the query in the
     * body, so {@code path}/{@code method} are implicit and not required; {@code query} is.
     */
    public static ConfigDef perGraphQlEndpointDef() {
        return new ConfigDef()
            .requireCustom("id", Type.STRING, Importance.HIGH,
                ID_DESCRIPTION,
                ConfigDefValidators.matchesRegex(ID_REGEX, ID_REGEX_DESCRIPTION))
            .requireCustom("query", Type.STRING, Importance.HIGH,
                "The GraphQL query document (with $cursor variable for relay pagination)",
                ConfigDefValidators.nonEmpty())
            .requireNested(PAGINATION_KEY, Importance.HIGH,
                PAGINATION_DESC, paginationDef())
            .requireNested(TARGET_KEY, Importance.HIGH,
                TARGET_DESC, targetDef());
    }

    /**
     * SOAP endpoints POST a rendered XML envelope to {@code base_url + path}, so {@code body} (the
     * template) is required; {@code header} and {@code soap_action} are optional; {@code method} is
     * implicit (POST). {@code path}, {@code pagination}, {@code target} as for REST.
     */
    public static ConfigDef perSoapEndpointDef() {
        return new ConfigDef()
            .requireCustom("id", Type.STRING, Importance.HIGH,
                ID_DESCRIPTION,
                ConfigDefValidators.matchesRegex(ID_REGEX, ID_REGEX_DESCRIPTION))
            .require("path", Type.STRING, Importance.HIGH,
                "SOAP service path appended to base_url, e.g. /eai_enu/start.swe")
            .requireCustom("body", Type.STRING, Importance.HIGH,
                "The SOAP body template (inner <Body> content; supports {{page_size}}/{{cursor}}/{{secret:NAME}}/{{env:NAME}})",
                ConfigDefValidators.nonEmpty())
            .requireNested(PAGINATION_KEY, Importance.HIGH,
                PAGINATION_DESC, paginationDef())
            .requireNested(TARGET_KEY, Importance.HIGH,
                TARGET_DESC, targetDef());
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
