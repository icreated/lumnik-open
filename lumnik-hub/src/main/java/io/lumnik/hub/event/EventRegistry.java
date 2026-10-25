package io.lumnik.hub.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.lumnik.hub.manifest.HubYaml;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.*;

@Startup
@ApplicationScoped
public class EventRegistry {

    private static final Logger LOG = Logger.getLogger(EventRegistry.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final YAMLMapper YAML_MAPPER = HubYaml.mapper();

    private List<EventCatalog.EventDescriptor> events = List.of();

    @PostConstruct
    public void scan() {
        JsonSchema schema = loadSchema();
        YAMLMapper yaml = YAML_MAPPER;
        List<EventCatalog.EventDescriptor> all = new ArrayList<>();
        try {
            Enumeration<URL> urls = Thread.currentThread().getContextClassLoader()
                                          .getResources("META-INF/events.yaml");
            int yamlCount = 0;
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                yamlCount++;
                try (InputStream in = url.openStream()) {
                    JsonNode doc = yaml.readTree(in);
                    Set<ValidationMessage> errors = schema.validate(doc);
                    if (!errors.isEmpty()) {
                        String msg = "Invalid events.yaml at " + url + ":\n  " +
                                     String.join("\n  ",
                                       errors.stream().map(ValidationMessage::getMessage).toList());
                        throw new IllegalStateException(msg);
                    }
                    String module = doc.get("module").asText();
                    for (JsonNode e : doc.get("events")) {
                        Map<String, String> payload = new LinkedHashMap<>();
                        if (e.has("payload"))
                            e.get("payload").fields().forEachRemaining(
                                f -> payload.put(f.getKey(), f.getValue().asText()));
                        all.add(new EventCatalog.EventDescriptor(
                            module,
                            e.get("type").asText(),
                            e.get("description").asText(),
                            Map.copyOf(payload)));
                    }
                }
            }
            events = List.copyOf(all);
            LOG.infof("Events loaded: %d event types from %d events.yaml files", events.size(), yamlCount);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan events.yaml files", e);
        }
    }

    private JsonSchema loadSchema() {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("META-INF/schemas/events-schema.json")) {
            if (in == null) throw new IllegalStateException("events-schema.json not on classpath");
            JsonNode node = MAPPER.readTree(in);
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(node);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load events-schema.json", e);
        }
    }

    public List<EventCatalog.EventDescriptor> all() { return events; }

    public Optional<EventCatalog.EventDescriptor> find(String type) {
        return events.stream().filter(e -> e.type().equals(type)).findFirst();
    }
}
