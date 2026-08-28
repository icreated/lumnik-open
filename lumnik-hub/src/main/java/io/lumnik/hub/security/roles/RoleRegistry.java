package io.lumnik.hub.security.roles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.lumnik.hub.manifest.ModuleManifest;
import io.lumnik.hub.manifest.ModuleManifestRegistry;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

@ApplicationScoped
public class RoleRegistry {

    private static final Logger LOG = Logger.getLogger(RoleRegistry.class);
    private static final String YAML_PATH = "META-INF/roles.yaml";
    private static final String SCHEMA_PATH = "META-INF/schemas/roles-schema.json";

    /** The grant/role list key in roles.yaml. */
    private static final String PERMISSIONS = "permissions";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final YAMLMapper YAML_MAPPER = new YAMLMapper();

    @Inject ModuleManifestRegistry manifestRegistry;

    private List<RoleDefinition> roles = List.of();

    void onStart(@Observes @Priority(2) StartupEvent ev) {
        Set<String> knownPermissions = collectKnownPermissions(manifestRegistry);
        List<URL> urls = scanClasspath();
        loadFrom(urls, knownPermissions);
        LOG.infof("Roles loaded: %d roles from %d roles.yaml files",
            roles.size(), urls.size());
    }

    /** A module adding its own permissions to a role defined elsewhere. */
    private record Grant(String role, List<String> permissions, URL source) {}

    // package-visible for unit testing
    void loadFrom(List<URL> urls, Set<String> knownPermissions) {
        JsonSchema schema = loadSchema();
        YAMLMapper yaml = YAML_MAPPER;
        List<RoleDefinition> all = new ArrayList<>();
        List<Grant> grants = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        for (URL url : urls) {
            try (InputStream in = url.openStream()) {
                JsonNode doc = yaml.readTree(in);
                validateAgainstSchema(doc, schema, url);
                if (doc.has("roles")) {
                    for (JsonNode r : doc.get("roles")) {
                        all.add(parseRole(r, url, seenIds, knownPermissions));
                    }
                }
                if (doc.has("grants")) {
                    for (JsonNode g : doc.get("grants")) {
                        grants.add(parseGrant(g, url, knownPermissions));
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read roles.yaml at " + url, e);
            }
        }
        // Applied after every file is read: the classpath order of getResources() is unspecified,
        // so a grant must not depend on its target role having been parsed first.
        this.roles = List.copyOf(applyGrants(all, grants));
        warnOnOrphanPermissions(knownPermissions);
    }

    private Grant parseGrant(JsonNode g, URL url, Set<String> knownPermissions) {
        String role = g.get("role").asText();
        List<String> perms = new ArrayList<>();
        for (JsonNode p : g.get(PERMISSIONS)) {
            String permId = p.asText();
            if (!knownPermissions.contains(permId)) {
                throw new IllegalStateException("Grant to role '" + role + "' at " + url +
                    " references unknown permission '" + permId +
                    "'. Known: " + new TreeSet<>(knownPermissions));
            }
            perms.add(permId);
        }
        return new Grant(role, perms, url);
    }

    private List<RoleDefinition> applyGrants(List<RoleDefinition> roleList, List<Grant> grants) {
        if (grants.isEmpty()) return roleList;
        Map<String, List<String>> added = new LinkedHashMap<>();
        Set<String> definedIds = roleList.stream().map(RoleDefinition::id).collect(Collectors.toSet());
        for (Grant g : grants) {
            // A typo here would silently grant nothing at all. Roles are security surface: fail loudly.
            if (!definedIds.contains(g.role())) {
                throw new IllegalStateException("Grant at " + g.source() + " targets unknown role '" +
                    g.role() + "'. Defined: " + new TreeSet<>(definedIds));
            }
            added.computeIfAbsent(g.role(), k -> new ArrayList<>()).addAll(g.permissions());
        }
        return roleList.stream().map(r -> {
            List<String> extra = added.get(r.id());
            if (extra == null) return r;
            List<String> merged = new ArrayList<>(r.permissions());
            extra.stream().filter(p -> !merged.contains(p)).forEach(merged::add);
            return new RoleDefinition(r.id(), r.label(), r.description(), r.realm(), r.wildcard(), merged);
        }).toList();
    }

    private void validateAgainstSchema(JsonNode doc, JsonSchema schema, URL url) {
        Set<ValidationMessage> errors = schema.validate(doc);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Invalid roles.yaml at " + url + ":\n  " +
                String.join("\n  ", errors.stream().map(ValidationMessage::getMessage).toList()));
        }
    }

    private RoleDefinition parseRole(JsonNode r, URL url, Set<String> seenIds, Set<String> knownPermissions) {
        String id = r.get("id").asText();
        if (!seenIds.add(id)) {
            throw new IllegalStateException("Duplicate role id '" + id + "' detected at " + url);
        }
        boolean wildcard = r.has("wildcard") && r.get("wildcard").asBoolean();
        return new RoleDefinition(
            id,
            r.get("label").asText(),
            r.get("description").asText(),
            r.get("realm").asBoolean(),
            wildcard,
            parsePermissions(r, id, knownPermissions));
    }

    private List<String> parsePermissions(JsonNode r, String roleId, Set<String> knownPermissions) {
        List<String> perms = new ArrayList<>();
        if (!r.has(PERMISSIONS)) return perms;
        for (JsonNode p : r.get(PERMISSIONS)) {
            String permId = p.asText();
            if (!knownPermissions.contains(permId)) {
                throw new IllegalStateException(
                    "Role '" + roleId + "' references unknown permission '" + permId +
                    "'. Known: " + new TreeSet<>(knownPermissions));
            }
            perms.add(permId);
        }
        return perms;
    }

    private List<URL> scanClasspath() {
        List<URL> urls = new ArrayList<>();
        try {
            Enumeration<URL> e = Thread.currentThread().getContextClassLoader()
                .getResources(YAML_PATH);
            while (e.hasMoreElements()) urls.add(e.nextElement());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan " + YAML_PATH, e);
        }
        return urls;
    }

    private JsonSchema loadSchema() {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(SCHEMA_PATH)) {
            if (in == null) throw new IllegalStateException(SCHEMA_PATH + " not on classpath");
            JsonNode node = MAPPER.readTree(in);
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(node);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load " + SCHEMA_PATH, e);
        }
    }

    static Set<String> collectKnownPermissions(ModuleManifestRegistry reg) {
        Set<String> ids = new HashSet<>();
        for (ModuleManifest m : reg.all()) {
            if (m.permissions() == null) continue;
            for (ModuleManifest.ManifestPermission p : m.permissions()) {
                ids.add(p.id());
            }
        }
        return Collections.unmodifiableSet(ids);
    }

    private void warnOnOrphanPermissions(Set<String> knownPermissions) {
        Set<String> granted = new HashSet<>();
        boolean anyWildcard = false;
        for (RoleDefinition r : roles) {
            if (r.wildcard()) anyWildcard = true;
            granted.addAll(r.permissions());
        }
        if (anyWildcard) return;
        Set<String> orphans = new TreeSet<>(knownPermissions);
        orphans.removeAll(granted);
        if (!orphans.isEmpty()) {
            LOG.warnf("Orphan permissions (declared by modules, granted by no role): %s",
                orphans);
        }
    }

    public List<RoleDefinition> all() {
        return roles;
    }
}
