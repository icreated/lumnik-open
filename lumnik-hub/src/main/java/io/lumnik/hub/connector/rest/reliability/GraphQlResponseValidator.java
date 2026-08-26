package io.lumnik.hub.connector.rest.reliability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.rest.RestResponse;
import io.lumnik.hub.connector.rest.Snippets;

/**
 * GraphQL returns HTTP 200 even on failure, carrying an {@code errors[]} array. Without this,
 * a failed query reads as an empty successful batch (silent data loss). This validator fails the
 * page (a thrown {@link ConnectorException} — permanent, no retry → the chunk dead-letters) when:
 * <ul>
 *   <li>the body is empty or not valid JSON (e.g. an HTML proxy/WAF error page served with 200),</li>
 *   <li>the body is not a JSON object,</li>
 *   <li>{@code errors} is present but not an array (a malformed envelope),</li>
 *   <li>{@code errors} is a non-empty array, or</li>
 *   <li>there are no errors and {@code data} is null/absent (a success envelope MUST carry data;
 *       otherwise the run would silently ingest an empty batch).</li>
 * </ul>
 * It re-parses the raw body rather than {@link RestResponse#json()}: the latter silently yields an
 * empty object on a parse failure, which would re-hide exactly these cases.
 *
 * <p>Only <b>2xx</b> responses are inspected. A non-2xx (408/429/5xx, often with an HTML body) is a
 * transport-level outcome left to {@code RetryExecutor}'s status-based retry — this validator runs
 * before that decision, so throwing here would turn retryable failures into permanent ones.
 * Partial-success ingestion ({@code on_error: warn}) is deliberately out of scope for E1.
 */
public final class GraphQlResponseValidator implements ResponseValidator {

    private static final ObjectMapper M = new ObjectMapper();

    @Override
    public void validate(RestResponse response) {
        // Only a 2xx response is a GraphQL envelope to inspect. A non-2xx (e.g. 502/503/429 with an
        // HTML body) is a transport-level outcome: RetryExecutor runs this validator BEFORE its
        // status-based retry, so throwing a permanent error here would turn retryable 408/429/5xx
        // into permanent chunk failures. Defer those to the executor's retry logic.
        if (response.status() / 100 != 2) {
            return;
        }
        String body = response.body();
        if (body == null || body.isBlank()) {
            throw new ConnectorException("GraphQL response body is empty (expected a JSON envelope with data/errors)");
        }
        JsonNode root;
        try {
            root = M.readTree(body);
        } catch (Exception e) {
            throw new ConnectorException("GraphQL response is not valid JSON: " + Snippets.bound(body), e);
        }
        if (root == null || !root.isObject()) {
            throw new ConnectorException("GraphQL response is not a JSON object: " + Snippets.bound(body));
        }

        JsonNode errors = root.get("errors");
        if (errors != null && !errors.isNull()) {
            // GraphQL errors MUST be a list; a non-array errors is a malformed envelope that would
            // otherwise slip past the non-empty check below and ingest nothing.
            if (!errors.isArray()) {
                throw new ConnectorException("GraphQL response 'errors' must be an array: " + Snippets.bound(body));
            }
            if (!errors.isEmpty()) {
                String first = errors.get(0).path("message").asText("(no message)");
                throw new ConnectorException(
                    "GraphQL response carried " + errors.size() + " error(s); first: " + first);
            }
        }

        // No usable errors → a valid success envelope must carry non-null data; otherwise the run
        // would silently ingest an empty batch (e.g. { "data": null } or a non-GraphQL body).
        JsonNode data = root.get("data");
        if (data == null || data.isNull()) {
            throw new ConnectorException(
                "GraphQL response has no errors and null/absent data (malformed envelope): " + Snippets.bound(body));
        }
    }

}
