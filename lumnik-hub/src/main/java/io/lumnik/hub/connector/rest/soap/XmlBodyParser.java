package io.lumnik.hub.connector.rest.soap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.xml.XmlFactory;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.exceptions.TransientFailureException;
import io.lumnik.hub.connector.rest.DottedPath;
import io.lumnik.hub.connector.rest.RestResponse;
import io.lumnik.hub.connector.rest.Snippets;
import io.lumnik.hub.connector.rest.reliability.RetryPolicy;
import io.lumnik.hub.connector.rest.runtime.BodyParser;

import javax.xml.stream.XMLInputFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Converts a SOAP XML response into a {@link JsonNode} so the existing JsonPath pagination/items
 * machinery runs unchanged. Two deterministic normalizations (the "minimum of C"):
 * <ol>
 *   <li><b>Namespace-prefix strip</b> — {@code soap:Body} → {@code Body}, so JsonPaths stay clean.</li>
 *   <li><b>Force-list at {@code response_path}</b> — the classic "one element → object, many → array"
 *       trap. The node at {@code response_path} is coerced to a list, always — the XML twin of
 *       {@code JsonPaths}' silent-data-loss guard. v1 supports a plain dotted path (no JsonPath
 *       operators) for the force-list; the row extraction itself still uses full JsonPath.
 *       <b>v1 boundary:</b> only the TERMINAL path segment is coerced — if an intermediate
 *       wrapper segment is itself repeated (an array mid-walk), the force-list is a no-op for
 *       that branch.</li>
 * </ol>
 */
public final class XmlBodyParser implements BodyParser {

    // Explicit XXE protection: disable DTD and external-entity resolution so a malicious SOAP
    // response cannot trigger file-read or SSRF via entity expansion. The default Woodstox impl
    // already rejects DTDs, but this makes the guard explicit and implementation-independent.
    private static final XmlMapper XML;
    static {
        XMLInputFactory xif = XMLInputFactory.newInstance();
        xif.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        xif.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        XML = XmlMapper.builder(new XmlFactory(xif)).build();
    }

    private final String responsePath;

    public XmlBodyParser(String responsePath) {
        this.responsePath = responsePath;
    }

    // Derived from RetryPolicy.defaults() so the two stay in sync when the retry config changes.
    private static final java.util.Set<Integer> RETRYABLE = RetryPolicy.defaults().retryOn();

    @Override
    public RestResponse reparse(RestResponse raw) {
        if (raw.status() >= 400) {
            // SoapFaultValidator (inside the retry boundary) has already handled real SOAP Faults.
            // Any non-2xx that survives here is an infrastructure error — classify by retryability:
            // 5xx/429/408 are transient (chunk retried later); 4xx misconfigurations are permanent.
            if (RETRYABLE.contains(raw.status())) {
                throw new TransientFailureException(
                    "SOAP endpoint returned HTTP " + raw.status() + " — treating as transient", null);
            }
            throw new ConnectorException("SOAP endpoint returned HTTP " + raw.status() + " — permanent failure");
        }
        JsonNode tree = strippedTree(raw.body());
        if (tree instanceof ObjectNode obj) forceList(obj, responsePath);
        return new RestResponse(raw.status(), raw.headers(), raw.body(), tree);
    }

    /** Parse XML → JSON with namespace prefixes stripped. Shared with {@link SoapFaultValidator}. */
    public static JsonNode strippedTree(String body) {
        if (body == null || body.isBlank()) return JsonNodeFactory.instance.objectNode();
        JsonNode parsed;
        try {
            parsed = XML.readTree(body);
        } catch (Exception e) {
            throw new ConnectorException("SOAP response is not valid XML: " + Snippets.bound(body), e);
        }
        return stripNamespaces(parsed);
    }

    /** Recursively rename {@code prefix:local} keys to {@code local}. Last-writer-wins on a collision. */
    private static JsonNode stripNamespaces(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
            obj.fields().forEachRemaining(entries::add);
            for (Map.Entry<String, JsonNode> e : entries) {
                String key = e.getKey();
                int colon = key.indexOf(':');
                String local = colon >= 0 ? key.substring(colon + 1) : key;
                out.set(local, stripNamespaces(e.getValue()));
            }
            return out;
        }
        if (node instanceof ArrayNode arr) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            arr.forEach(child -> out.add(stripNamespaces(child)));
            return out;
        }
        return node;
    }

    /** Coerce the node at a plain dotted {@code response_path} (after {@code $.}) to an array. No-op
     *  when the path uses JsonPath operators or is absent in the tree.
     *  <p>Only the TERMINAL segment is coerced: the walk descends through {@link ObjectNode}
     *  intermediates only, so if an intermediate path segment is itself a repeated (array) element
     *  the walk returns early and the force-list is a no-op for that branch (v1 limitation). */
    private static void forceList(ObjectNode root, String responsePath) {
        // Same predicate the connector validator enforces up front (validator==runtime): only a
        // plain dotted path can be walked here — anything else is a deliberate no-op.
        if (!DottedPath.isPlain(responsePath)) return;
        String[] segs = DottedPath.segments(responsePath);
        ObjectNode parent = root;
        for (int i = 0; i < segs.length - 1; i++) {
            JsonNode child = parent.get(segs[i]);
            if (!(child instanceof ObjectNode on)) return; // path absent → no items to coerce
            parent = on;
        }
        String leaf = segs[segs.length - 1];
        JsonNode leafNode = parent.get(leaf);
        if (leafNode != null && leafNode.isObject()) {
            ArrayNode arr = parent.arrayNode();
            arr.add(leafNode);
            parent.set(leaf, arr);
        }
    }
}
