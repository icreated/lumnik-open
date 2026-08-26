package io.lumnik.hub.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.*;

@Startup
@ApplicationScoped
public class HookManifestScanner {

    private static final String DESCRIPTION = "description";

    private static final Logger LOG = Logger.getLogger(HookManifestScanner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final YAMLMapper YAML_MAPPER = new YAMLMapper();

    @Inject HookRegistry registry;

    @PostConstruct
    public void scan() {
        JsonSchema schema = loadSchema();
        YAMLMapper yaml = YAML_MAPPER;
        try {
            Enumeration<URL> urls = Thread.currentThread().getContextClassLoader()
                                          .getResources("META-INF/hooks.yaml");
            int yamlCount = 0;
            int hookCount = 0;
            int calloutCount = 0;
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                yamlCount++;
                try (InputStream in = url.openStream()) {
                    JsonNode doc = yaml.readTree(in);
                    Set<ValidationMessage> errors = schema.validate(doc);
                    if (!errors.isEmpty()) {
                        String msg = "Invalid hooks.yaml at " + url + ":\n  " +
                                     String.join("\n  ",
                                       errors.stream().map(ValidationMessage::getMessage).toList());
                        throw new IllegalStateException(msg);
                    }
                    String module = doc.path("module").asText();
                    hookCount    += loadHooks(doc, module);
                    calloutCount += loadCallouts(doc, module);
                }
            }
            LOG.infof("Hooks loaded: %d hooks, %d callouts from %d hooks.yaml files",
                      hookCount, calloutCount, yamlCount);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan hooks.yaml files", e);
        }
    }

    private JsonSchema loadSchema() {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("META-INF/schemas/hooks-schema.json")) {
            if (in == null) throw new IllegalStateException("hooks-schema.json not on classpath");
            JsonNode node = MAPPER.readTree(in);
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(node);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load hooks-schema.json", e);
        }
    }

    private int loadHooks(JsonNode doc, String module) {
        if (!doc.has("hooks")) return 0;
        int count = 0;
        for (JsonNode h : doc.get("hooks")) {
            registry.register(toBinding(h, module));
            count++;
        }
        return count;
    }

    private int loadCallouts(JsonNode doc, String module) {
        if (!doc.has("callouts")) return 0;
        int count = 0;
        for (JsonNode c : doc.get("callouts")) {
            registry.registerCallout(toCalloutBinding(c, module));
            count++;
        }
        return count;
    }

    private HookBinding toBinding(JsonNode h, String module) {
        Set<LifecycleEvent> events = EnumSet.noneOf(LifecycleEvent.class);
        if (h.has("events"))
            h.get("events").forEach(e -> events.add(LifecycleEvent.valueOf(e.asText())));

        String action = h.has("action") ? h.get("action").asText() : null;
        LifecycleEvent.Phase phase = h.has("when")
            ? LifecycleEvent.Phase.valueOf(h.get("when").asText()) : null;

        List<Object> tenants = new ArrayList<>();
        if (h.has("tenants"))
            for (JsonNode t : h.get("tenants"))
                tenants.add(t.isNumber() ? t.asLong() : t.asText());
        else tenants.add("*");

        return new HookBinding(
            h.get("id").asText(),
            module,
            h.has(DESCRIPTION) ? h.get(DESCRIPTION).asText() : "",
            h.has("target") ? h.get("target").asText() : null,
            events,
            action,
            phase,
            handlerOf(h.get("handler")),
            h.has("priority") ? h.get("priority").asInt() : 100,
            tenants,
            !h.has("enabled") || h.get("enabled").asBoolean()
        );
    }

    private CalloutBinding toCalloutBinding(JsonNode c, String module) {
        return new CalloutBinding(
            c.get("id").asText(),
            module,
            c.has(DESCRIPTION) ? c.get(DESCRIPTION).asText() : "",
            c.get("entity").asText(),
            c.get("field").asText(),
            handlerOf(c.get("handler"))
        );
    }

    private HookHandler handlerOf(JsonNode handlerNode) {
        String type = handlerNode.get("type").asText();
        return switch (type) {
            case "java"   -> new HookHandler.JavaHandler(handlerNode.get("class").asText());
            case "kotlin" -> new HookHandler.KotlinHandler(handlerNode.get("script").asText());
            default       -> throw new IllegalStateException("Unknown handler type: " + type);
        };
    }
}
