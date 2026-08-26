package io.lumnik.hub.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.lumnik.hub.manifest.ManifestKeys;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parses a kind:Workflow YAML manifest — the declarative document lifecycle. Structural
 * refusals only; the data-truth confrontation (values actually present in the table) is
 * WorkflowDataTruth's job at apply time. An ambiguous alias is a contradiction in the
 * declaration and is refused; an undeclared VALUE in the data is reality and is reported,
 * never refused.
 */
public class WorkflowManifestParser {

    private static final YAMLMapper MAPPER = new YAMLMapper();
    private static final String METADATA = "metadata";

    private static final Set<String> ROOT_KEYS = Set.of("apiVersion", "kind", METADATA, "spec");
    private static final Set<String> METADATA_KEYS = Set.of("name");
    private static final Set<String> SPEC_KEYS =
        Set.of("scope", "provenance", "on", "states", "transitions", "dormancy");
    private static final Set<String> ON_KEYS = Set.of("table", "column", "key");
    private static final Set<String> STATE_KEYS = Set.of("code", "label", "aliases", "initial", "terminal");
    private static final Set<String> TRANSITION_KEYS = Set.of("from", "to");
    private static final Set<String> DORMANCY_KEYS = Set.of("after");

    private static final Map<String, Set<String>> ALLOWED_BY_PATH = new LinkedHashMap<>();
    static {
        ALLOWED_BY_PATH.put("", ROOT_KEYS);
        ALLOWED_BY_PATH.put(METADATA, METADATA_KEYS);
        ALLOWED_BY_PATH.put("spec", SPEC_KEYS);
        ALLOWED_BY_PATH.put("spec.on", ON_KEYS);
        ALLOWED_BY_PATH.put("spec.dormancy", DORMANCY_KEYS);
        ALLOWED_BY_PATH.put("spec.states[]", STATE_KEYS);
        ALLOWED_BY_PATH.put("spec.transitions[]", TRANSITION_KEYS);
    }

    // Identifier guards: the table/column ride into a SQL statement at apply time —
    // the grammar is closed here so injection dies at declaration.
    private static final String TABLE_RX = "connector\\.[a-z_][a-z0-9_]*";
    private static final String COLUMN_RX = "[a-z_][a-z0-9_]*";
    private static final String AFTER_RX = "[0-9]+[mhd]";

    /** Alias/code matching normalization — trim + lowercase. Excel reality is dirty input, not error. */
    public static String norm(String s) {
        return s.trim().toLowerCase(Locale.ROOT);
    }

    public WorkflowManifest parse(String yaml) {
        JsonNode root;
        try {
            root = MAPPER.readTree(yaml);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid kind:Workflow manifest: " + e.getMessage(), e);
        }
        if (!"Workflow".equals(ManifestKeys.text(root, "kind")))
            throw new IllegalArgumentException("kind must be 'Workflow'");
        JsonNode meta = root.path(METADATA);
        JsonNode spec = root.path("spec");
        String name = ManifestKeys.text(meta, "name");
        if (name == null || !name.matches("[a-z0-9-]+"))
            throw new IllegalArgumentException(
                "metadata.name is required and must be a simple identifier (a-z, 0-9, -): " + name);
        String scope = ManifestKeys.text(spec, "scope");
        if (scope == null || scope.isBlank())
            throw new IllegalArgumentException("spec.scope is required");
        String provenance = ManifestKeys.text(spec, "provenance");
        if (provenance == null || provenance.isBlank())
            throw new IllegalArgumentException("spec.provenance is required ('declared')");
        if (!"declared".equals(provenance))
            throw new IllegalArgumentException("spec.provenance '" + provenance
                + "' not yet supported (v1 accepts only 'declared')");
        JsonNode on = spec.path("on");
        String table = ManifestKeys.text(on, "table");
        if (table == null || !table.matches(TABLE_RX))
            throw new IllegalArgumentException(
                "spec.on.table is required and must match " + TABLE_RX + ": " + table);
        String column = ManifestKeys.text(on, "column");
        if (column == null || !column.matches(COLUMN_RX))
            throw new IllegalArgumentException(
                "spec.on.column is required and must match " + COLUMN_RX + ": " + column);

        String key = ManifestKeys.text(on, "key");
        if (key != null && !key.matches(COLUMN_RX))
            throw new IllegalArgumentException(
                "spec.on.key must match " + COLUMN_RX + ": " + key);

        WorkflowManifest.DormancyDef dormancy = null;
        JsonNode dorm = spec.path("dormancy");
        if (!dorm.isMissingNode() && !dorm.isNull()) {
            if (key == null)
                throw new IllegalArgumentException(
                    "spec.dormancy requires spec.on.key (dormancy is per-document; "
                        + "the key links a document's versions)");
            String after = ManifestKeys.text(dorm, "after");
            if (after == null || !after.matches(AFTER_RX))
                throw new IllegalArgumentException(
                    "spec.dormancy.after is required and must match " + AFTER_RX
                        + " (e.g. 30d, 12h, 2m): " + after);
            dormancy = new WorkflowManifest.DormancyDef(after);
        }

        List<WorkflowManifest.StateDef> states = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        Map<String, String> aliasOwner = new HashMap<>(); // normalized alias -> state code
        int initials = 0;
        for (JsonNode s : spec.path("states")) {
            String code = ManifestKeys.text(s, "code");
            if (code == null || code.isBlank())
                throw new IllegalArgumentException("every state needs a code");
            if (!codes.add(norm(code)))
                throw new IllegalArgumentException("duplicate state code: " + code);
            boolean initial = s.path("initial").asBoolean(false);
            if (initial) initials++;
            List<String> aliases = new ArrayList<>();
            for (JsonNode a : s.path("aliases")) aliases.add(a.asText());
            states.add(new WorkflowManifest.StateDef(code, ManifestKeys.text(s, "label"),
                List.copyOf(aliases), initial, s.path("terminal").asBoolean(false)));
        }
        if (states.isEmpty())
            throw new IllegalArgumentException("spec.states must declare at least one state");
        if (initials != 1)
            throw new IllegalArgumentException("exactly one state must be initial: true (found " + initials + ")");
        for (WorkflowManifest.StateDef s : states) {
            for (String a : s.aliases()) {
                String n = norm(a);
                if (codes.contains(n))
                    throw new IllegalArgumentException(
                        "alias '" + a + "' on state " + s.code() + " collides with state code");
                String owner = aliasOwner.putIfAbsent(n, s.code());
                if (owner != null && !owner.equals(s.code()))
                    throw new IllegalArgumentException("alias '" + a + "' is ambiguous: declared on both "
                        + owner + " and " + s.code());
            }
        }

        List<WorkflowManifest.TransitionDef> transitions = new ArrayList<>();
        Set<String> terminals = new HashSet<>();
        states.stream().filter(WorkflowManifest.StateDef::terminal)
            .forEach(s -> terminals.add(norm(s.code())));
        for (JsonNode t : spec.path("transitions")) {
            List<String> from = new ArrayList<>();
            for (JsonNode f : t.path("from")) from.add(f.asText());
            String to = ManifestKeys.text(t, "to");
            if (from.isEmpty() || to == null)
                throw new IllegalArgumentException("every transition needs from: [..] and to:");
            for (String f : from) {
                if (!codes.contains(norm(f)))
                    throw new IllegalArgumentException("transition from undeclared state '" + f + "'");
                if (terminals.contains(norm(f)))
                    throw new IllegalArgumentException(
                        "terminal state '" + f + "' has an outgoing transition");
            }
            if (!codes.contains(norm(to)))
                throw new IllegalArgumentException("transition to undeclared state '" + to + "'");
            transitions.add(new WorkflowManifest.TransitionDef(List.copyOf(from), to));
        }
        return new WorkflowManifest(name, scope, provenance, table, column, key, dormancy,
            List.copyOf(states), List.copyOf(transitions));
    }

    /** Unknown keys as dotted paths — reporting only; the apply door refuses. Mirror of kind:Process C3. */
    public List<String> unknownKeys(String yaml) {
        JsonNode root;
        try {
            root = MAPPER.readTree(yaml);
        } catch (Exception e) {
            return List.of();
        }
        return ManifestKeys.unknownKeys(root, ALLOWED_BY_PATH);
    }

    public static String honoredSurface() {
        return "root {" + sorted(ROOT_KEYS) + "}, metadata {" + sorted(METADATA_KEYS)
            + "}, spec {" + sorted(SPEC_KEYS) + "}, spec.on {" + sorted(ON_KEYS)
            + "}, spec.dormancy {" + sorted(DORMANCY_KEYS)
            + "}, states[] {" + sorted(STATE_KEYS) + "}, transitions[] {" + sorted(TRANSITION_KEYS) + "}";
    }

    private static String sorted(Set<String> keys) {
        return String.join(", ", new java.util.TreeSet<>(keys));
    }
}
