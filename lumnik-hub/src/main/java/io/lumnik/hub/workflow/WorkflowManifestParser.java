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
import java.util.TreeSet;

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

    /**
     * A manifest is a CATALOGUE of rules, not a knot: an envelope, the column it watches, the states,
     * the transitions. Each movement is named for what it requires, so this method reads as the table
     * of contents it always was. All 21 refusals are pinned one-for-one by WorkflowManifestParserTest,
     * which is what makes the split safe.
     */
    public WorkflowManifest parse(String yaml) {
        JsonNode root = readTree(yaml);
        requireWorkflowKind(root);
        JsonNode meta = root.path(METADATA);
        JsonNode spec = root.path("spec");

        String name = requireSimpleName(meta);
        String scope = requireScope(spec);
        String provenance = requireDeclaredProvenance(spec);

        Watched watched = requireWatchedColumn(spec);
        WorkflowManifest.DormancyDef dormancy = optionalDormancy(spec, watched.key());

        Declared declared = requireStates(spec);
        List<WorkflowManifest.TransitionDef> transitions = requireTransitions(spec, declared);

        return new WorkflowManifest(name, scope, provenance, watched.table(), watched.column(),
            watched.key(), dormancy, List.copyOf(declared.states()), List.copyOf(transitions));
    }

    /** The column this workflow watches, and the optional business key linking a document's versions. */
    private record Watched(String table, String column, String key) {}

    /** The declared states, with their codes normalized once for every later membership test. */
    private record Declared(List<WorkflowManifest.StateDef> states, Set<String> codes) {}

    private JsonNode readTree(String yaml) {
        try {
            return MAPPER.readTree(yaml);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid kind:Workflow manifest: " + e.getMessage(), e);
        }
    }

    private static void requireWorkflowKind(JsonNode root) {
        if (!"Workflow".equals(ManifestKeys.text(root, "kind")))
            throw new IllegalArgumentException("kind must be 'Workflow'");
    }

    private static String requireSimpleName(JsonNode meta) {
        String name = ManifestKeys.text(meta, "name");
        if (name == null || !name.matches("[a-z0-9-]+"))
            throw new IllegalArgumentException(
                "metadata.name is required and must be a simple identifier (a-z, 0-9, -): " + name);
        return name;
    }

    private static String requireScope(JsonNode spec) {
        String scope = ManifestKeys.text(spec, "scope");
        if (scope == null || scope.isBlank())
            throw new IllegalArgumentException("spec.scope is required");
        return scope;
    }

    private static String requireDeclaredProvenance(JsonNode spec) {
        String provenance = ManifestKeys.text(spec, "provenance");
        if (provenance == null || provenance.isBlank())
            throw new IllegalArgumentException("spec.provenance is required ('declared')");
        if (!"declared".equals(provenance))
            throw new IllegalArgumentException("spec.provenance '" + provenance
                + "' not yet supported (v1 accepts only 'declared')");
        return provenance;
    }

    /**
     * The identifiers that ride into SQL. The grammars are closed here, at declaration, because JDBC
     * cannot bind an identifier — see WorkflowDormancySweep, which interpolates these on the admin
     * (BYPASSRLS) datasource.
     */
    private static Watched requireWatchedColumn(JsonNode spec) {
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
        return new Watched(table, column, key);
    }

    private static WorkflowManifest.DormancyDef optionalDormancy(JsonNode spec, String key) {
        JsonNode dorm = spec.path("dormancy");
        if (dorm.isMissingNode() || dorm.isNull()) return null;
        if (key == null)
            throw new IllegalArgumentException(
                "spec.dormancy requires spec.on.key (dormancy is per-document; "
                    + "the key links a document's versions)");
        String after = ManifestKeys.text(dorm, "after");
        if (after == null || !after.matches(AFTER_RX))
            throw new IllegalArgumentException(
                "spec.dormancy.after is required and must match " + AFTER_RX
                    + " (e.g. 30d, 12h, 2m): " + after);
        return new WorkflowManifest.DormancyDef(after);
    }

    private static Declared requireStates(JsonNode spec) {
        List<WorkflowManifest.StateDef> states = new ArrayList<>();
        Set<String> codes = new HashSet<>();
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
        refuseAmbiguousAliases(states, codes);
        return new Declared(states, codes);
    }

    /** An alias must name exactly one state, and must not shadow a code — dirty legacy values depend on it. */
    private static void refuseAmbiguousAliases(List<WorkflowManifest.StateDef> states, Set<String> codes) {
        Map<String, String> aliasOwner = new HashMap<>(); // normalized alias -> state code
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
    }

    private static List<WorkflowManifest.TransitionDef> requireTransitions(JsonNode spec, Declared declared) {
        Set<String> terminals = new HashSet<>();
        declared.states().stream().filter(WorkflowManifest.StateDef::terminal)
            .forEach(s -> terminals.add(norm(s.code())));

        List<WorkflowManifest.TransitionDef> transitions = new ArrayList<>();
        for (JsonNode t : spec.path("transitions")) {
            transitions.add(requireTransition(t, declared, terminals));
        }
        return transitions;
    }

    /**
     * One edge. Every refusal here is about the edge itself, which is why it reads at this level and
     * not inside the loop that collects them: an edge needs both ends, both ends must name declared
     * states, and a terminal state has no way out — that last one is the whole point of declaring a
     * state terminal.
     */
    private static WorkflowManifest.TransitionDef requireTransition(JsonNode t, Declared declared,
                                                                    Set<String> terminals) {
        List<String> from = new ArrayList<>();
        for (JsonNode f : t.path("from")) from.add(f.asText());
        String to = ManifestKeys.text(t, "to");
        if (from.isEmpty() || to == null)
            throw new IllegalArgumentException("every transition needs from: [..] and to:");
        for (String f : from) {
            if (!declared.codes().contains(norm(f)))
                throw new IllegalArgumentException("transition from undeclared state '" + f + "'");
            if (terminals.contains(norm(f)))
                throw new IllegalArgumentException(
                    "terminal state '" + f + "' has an outgoing transition");
        }
        if (!declared.codes().contains(norm(to)))
            throw new IllegalArgumentException("transition to undeclared state '" + to + "'");
        return new WorkflowManifest.TransitionDef(List.copyOf(from), to);
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
        return String.join(", ", new TreeSet<>(keys));
    }
}
