package io.lumnik.hub.connector.rest.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lumnik.common.text.Text;
import io.lumnik.hub.connector.exceptions.ConnectorException;
import io.lumnik.hub.connector.rest.ResponseStatusGuard;
import io.lumnik.hub.connector.rest.RestResponse;
import io.lumnik.hub.connector.rest.DottedPath;
import io.lumnik.hub.connector.rest.soap.XmlBodyParser;
import java.io.StringReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * A REST answer in XML, read when the endpoint declares {@code response_format: xml} (#1755) — PrestaShop's Webservice by
 * default, public feeds. The tree the JsonPaths walk, root element left out:
 * <ul>
 *   <li>an element with only text is its text: {@code <price>23.9</price>} → {@code "23.9"};</li>
 *   <li>attributes go under {@code @}, the text beside them under {@code #text}:
 *       {@code <language id="1">Hummingbird</language>} → {@code {"@id":"1","#text":"Hummingbird"}};</li>
 *   <li>a repeated child is a list; the node at {@code response_path} is a list even when it holds one row;</li>
 *   <li>namespace prefixes are dropped: {@code xlink:href} → {@code @href}.</li>
 * </ul>
 * No DTD and no external entity is ever read: a document that declares one is refused. A refusal names the
 * Content-Type, never the body. SOAP keeps its own reading ({@link XmlBodyParser}).
 */
public final class XmlRestBodyParser implements BodyParser {

    private final String responsePath;

    public XmlRestBodyParser(String responsePath) {
        this.responsePath = responsePath;
    }

    @Override
    public RestResponse reparse(RestResponse raw) {
        ResponseStatusGuard.refuseNonSuccess(raw);   // an error page is never read as rows
        JsonNode tree;
        try {
            // An empty answer is an empty page, as with JSON and SOAP: it ends the read, it is not a failure.
            tree = Text.blank(raw.body()) ? JsonNodeFactory.instance.objectNode() : read(raw.body());
        } catch (XMLStreamException unreadable) {
            throw new ConnectorException("the response is not readable XML (" + raw.contentTypeLabel() + ")");
        }
        if (tree instanceof ObjectNode obj) DottedPath.forceList(obj, responsePath);
        return new RestResponse(raw.status(), raw.headers(), raw.body(), tree);
    }

    /** The document's root element, as the tree under it. */
    private static JsonNode read(String body) throws XMLStreamException {
        // A factory per read: StAX promises no thread safety for a shared one, and runs read concurrently.
        XMLInputFactory stax = XMLInputFactory.newFactory();
        stax.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        stax.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);   // a second lock on the same door
        XMLStreamReader xml = stax.createXMLStreamReader(new StringReader(body));
        try {
            while (xml.hasNext()) {
                switch (xml.next()) {
                    case XMLStreamConstants.DTD -> throw new XMLStreamException("a document type is not read");
                    case XMLStreamConstants.START_ELEMENT -> { return element(xml); }
                    default -> { }
                }
            }
            throw new XMLStreamException("no root element");
        } finally {
            xml.close();
        }
    }

    /** The element the reader stands on, read to its end tag. */
    private static JsonNode element(XMLStreamReader xml) throws XMLStreamException {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        for (int i = 0; i < xml.getAttributeCount(); i++) {
            node.put("@" + xml.getAttributeLocalName(i), xml.getAttributeValue(i));
        }
        StringBuilder text = new StringBuilder();
        while (true) {
            switch (xml.next()) {
                case XMLStreamConstants.START_ELEMENT -> add(node, xml.getLocalName(), element(xml));
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> text.append(xml.getText());
                case XMLStreamConstants.END_ELEMENT -> {
                    String value = text.toString().strip();
                    if (node.isEmpty()) return JsonNodeFactory.instance.textNode(value);
                    if (!value.isEmpty()) node.put("#text", value);
                    return node;
                }
                default -> { }
            }
        }
    }

    /** A second child of the same name turns the first into a list. */
    private static void add(ObjectNode parent, String name, JsonNode child) {
        JsonNode held = parent.get(name);
        if (held == null) {
            parent.set(name, child);
        } else if (held instanceof ArrayNode list) {
            list.add(child);
        } else {
            ArrayNode list = parent.arrayNode();
            list.add(held);
            list.add(child);
            parent.set(name, list);
        }
    }
}
