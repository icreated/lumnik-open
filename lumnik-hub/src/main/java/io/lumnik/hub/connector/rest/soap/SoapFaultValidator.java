package io.lumnik.hub.connector.rest.soap;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.rest.RestResponse;
import io.lumnik.hub.connector.rest.Snippets;
import io.lumnik.hub.connector.rest.reliability.ResponseValidator;

/**
 * Fails a page permanently when the SOAP body carries a {@code <Fault>} ({@code $.Body.Fault} after
 * namespace strip). Unlike the GraphQL validator, it inspects EVERY status — SOAP 1.1 faults arrive
 * with HTTP 500, and a fault is a business error that must not be retried. A non-XML body (e.g. an
 * HTML 5xx page) is NOT a fault and is left to {@code RetryExecutor}'s status-based retry.
 */
public final class SoapFaultValidator implements ResponseValidator {

    @Override
    public void validate(RestResponse response) {
        String body = response.body();
        if (body == null || body.isBlank()) return;
        JsonNode tree;
        try {
            tree = XmlBodyParser.strippedTree(body);
        } catch (RuntimeException notXml) {
            return; // not parseable SOAP XML → defer to status-based retry
        }
        JsonNode fault = tree.path("Body").path("Fault");
        if (fault.isMissingNode() || fault.isNull()) return;
        String faultstring = fault.path("faultstring").asText("");
        // Both branches are bounded: faultstring can be arbitrarily large in some SOAP services.
        String msg = faultstring.isBlank() ? Snippets.bound(fault.toString()) : Snippets.bound(faultstring);
        throw new ConnectorException("SOAP Fault: " + msg);
    }
}
