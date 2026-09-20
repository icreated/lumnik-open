package io.lumnik.hub.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.hub.manifest.ManifestKeys;
import io.lumnik.hub.manifest.ManifestSurface;
import io.lumnik.hub.sql.SqlIdentifiers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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

    private static final String METADATA = "metadata";

    private static final Set<String> ROOT_KEYS = Set.of("apiVersion", "kind", METADATA, "spec");
    private static final Set<String> METADATA_KEYS = Set.of("name");
    private static final Set<String> SPEC_KEYS =
        Set.of("scope", "provenance", "on", "states", "transitions", "dormancy");
    private static final Set<String> ON_KEYS = Set.of("table", "column", "key");
    private static final Set<String> STATE_KEYS = Set.of("code", "label", "aliases", "initial", "terminal");
    private static final Set<String> TRANSITION_KEYS = Set.of("from", "to");
    private static final Set<String> DORMANCY_KEYS = Set.of("after");

    private static final ManifestSurface SURFACE = ManifestSurface.builder()
        .group("", "root", ROOT_KEYS)
        .group(METADATA, "metadata", METADATA_KEYS)
        .group("spec", "spec", SPEC_KEYS)
        .group("spec.on", "spec.on", ON_KEYS)
        .group("spec.dormancy", "spec.dormancy", DORMANCY_KEYS)
        .group("spec.states[]", "states[]", STATE_KEYS)
        .group("spec.transitions[]", "transitions[]", TRANSITION_KEYS)
        .build();

    /** What a workflow may watch — see {@link #requireWatchedTable}. {@code t_} is
     *  DynamicTableRegistry's ingest convention, {@code v_} an EntityViewCompiler fused view. */
    private static final String SCHEMA = "connector";
    private static final String SCHEMA_PREFIX = SCHEMA + ".";
    private static final String INGEST_PREFIX = SCHEMA_PREFIX + "t_";
    private static final String FUSED_VIEW_PREFIX = SCHEMA_PREFIX + "v_";
    private static final List<String> WATCHABLE = List.of(INGEST_PREFIX, FUSED_VIEW_PREFIX);
    /** {@code EntityManifestParser} admits {@code [A-Za-z0-9_]+} and {@code EntityViewCompiler}
     *  folds it — so the composed suffix is exactly that alphabet, lower-cased. A leading DIGIT is
     *  legal here and illegal under {@code t_}: the two prefixes do not share an alphabet. */
    private static final String FUSED_VIEW_NAME_RX = "[a-z0-9_]+";
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
        return parse(readTree(yaml));
    }

    public WorkflowManifest parse(JsonNode root) {
        requireWorkflowKind(root);
        JsonNode meta = root.path(METADATA);
        JsonNode spec = root.path("spec");

        String name = requireSimpleName(meta);
        String scope = requireScope(spec);
        String provenance = requireDeclaredProvenance(spec);

        Watched watched = requireWatchedColumn(spec);
        WorkflowManifest.DormancyDef dormancy = optionalDormancy(spec, watched);

        Declared declared = requireStates(spec);
        List<WorkflowManifest.TransitionDef> transitions = requireTransitions(spec, declared);

        return new WorkflowManifest(name, scope, provenance, watched.table(), watched.column(),
            watched.key(), dormancy, List.copyOf(declared.states()), List.copyOf(transitions));
    }

    /** The column this workflow watches, and the optional business key linking a document's versions. */
    private record Watched(String table, String column, String key) {}

    /** The declared states, with their codes normalized once for every later membership test. */
    private record Declared(List<WorkflowManifest.StateDef> states, Set<String> codes) {}

    /**
     * Parses once, catching broadly (SnakeYAML/Jackson raise a mix of checked and unchecked
     * failures for a null or malformed body — the existing message must fire for both; an empty
     * or blank-only body does not throw here at all, {@link #parse(JsonNode)} refuses it with its
     * ordinary "kind must be 'Workflow'" check instead). Package-visible so the door can call it
     * once and reuse the node for both parse() and unknownKeys().
     */
    JsonNode readTree(String yaml) {
        try {
            return SURFACE.readTree(yaml);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid kind:Workflow manifest: " + e.getMessage(), e);
        }
    }

    private static void requireWorkflowKind(JsonNode root) {
        if (!"Workflow".equals(ManifestKeys.text(root, "kind")))
            throw new IllegalArgumentException("kind must be 'Workflow'");
    }

    /**
     * Split from the formerly-fused "metadata.name is required and must be a simple
     * identifier..." sentence — a blank name and a malformed one are different rules and now get
     * different messages (#579's message-drift table).
     */
    private static String requireSimpleName(JsonNode meta) {
        String name = ManifestKeys.requiredText(meta, "name", "metadata.name");
        if (!name.matches("[a-z0-9-]+"))
            throw new IllegalArgumentException(
                "metadata.name must be a simple identifier (a-z, 0-9, -): " + name);
        return name;
    }

    private static String requireScope(JsonNode spec) {
        String scope = ManifestKeys.requiredText(spec, "scope", "spec.scope");
        // the tag becomes the case-sensitive Keycloak role scope:<tag> — the same rule
        // AppManifestParser, ViewManifestParser, ScopesValidator and EntityManifestParser ask (#577).
        ScopeGrants.nameProblem(scope)
            .ifPresent(problem -> { throw new IllegalArgumentException("spec.scope: " + problem); });
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
     * The identifiers that ride into SQL. They ride QUOTED — {@code SqlIdentifiers.quoteIdent} at
     * every builder (WorkflowDataTruth, WorkflowDormancySweep) — so escaping, not a character
     * grammar, is what stops an injection: a hostile name resolves to a column that does not exist.
     * This is the same defence kind:View has used since it shipped, and the reason the name itself
     * is free: ingestion lands a source's keys VERBATIM (DynamicTableWriter quotes each one), so
     * {@code Statut Commande}, {@code MONTANT_€} and {@code c_bpartner.id} are columns a customer
     * really has, and a grammar here would make their lifecycle undeclarable for a reason the
     * integrator cannot act on — the hub chose the name.
     *
     * <p>One refusal is NOT grammatical and stays: the watched table must be one ingestion owns
     * (see {@link #requireWatchedTable}). The dormancy sweep reads on the admin (BYPASSRLS)
     * datasource, so a workflow declared over {@code identity.app_user} would read every tenant's
     * rows and copy the watched values into {@code core.event_outbox} payloads. Quoting confines
     * nothing; that check does.
     */
    private static Watched requireWatchedColumn(JsonNode spec) {
        JsonNode on = spec.path("on");
        String table = requireWatchedTable(ManifestKeys.text(on, "table"));
        String column = requireNamed(ManifestKeys.text(on, "column"), "spec.on.column");
        String key = ManifestKeys.text(on, "key");
        if (key != null) requireNamed(key, "spec.on.key");
        return new Watched(table, column, key);
    }

    /**
     * WHERE a workflow may watch — the one refusal quoting cannot make, per the javadoc above.
     *
     * <p>The schema alone is not enough, and that is not a detail: {@code connector} holds twelve
     * HUB-INTERNAL tables beside the ingested ones ({@code connector_credential}, {@code schema_card},
     * {@code saved_view}, …) and {@code lumnik_readonly} is granted SELECT on all of them, so
     * watching one made the data-truth report hand its column's values back in the apply response.
     * The prefixes are the hub's own conventions for "a table ingestion created" — the same
     * blast-radius bound {@code DynamicTableRegistry.isHubDynamicTable} draws for purge, and the
     * reason this is an allowlist rather than a list of internal tables to keep up to date.
     *
     * <p>The SHAPE is checked with {@link SqlIdentifiers#isPlainIdentifier} — not a grammar invented
     * here, but the very predicate the hub COMPOSES these names under ({@code "t_" + name} in every
     * connector type, {@code "v_" + name} over the entity parser's {@code [A-Za-z0-9_]+}). It is the
     * one place a character rule is honest, because this name is ours and not the source's: it
     * accepts the capital in {@code t_Orders}, and refuses the trailing space in
     * {@code t_orders␠}, which cannot be a real table and would otherwise be reported as
     * {@code tableMissing} — the flag the page phrases as the legitimate "declared before ingesting"
     * case, so the typo would be answered with the wrong errand. A COLUMN is the opposite and keeps
     * its whitespace: the source chose that name, not us.
     *
     * <p>The length test is not redundant with the shape one: {@code isPlainIdentifier("t_")} is
     * TRUE, so the prefix alone would satisfy a shape check and apply as a table the hub can never
     * have created. Both halves are needed — the prefix must be a prefix OF something. See
     * {@link #composedAs} for the per-prefix clauses.
     */
    private static String requireWatchedTable(String table) {
        if (table == null || WATCHABLE.stream().noneMatch(p -> composedAs(table, p)))
            throw new IllegalArgumentException("spec.on.table is required and must name a table in "
                + "the '" + SCHEMA + "' schema that ingestion owns (" + SCHEMA + ".t_<table>) or a "
                + "fused entity view (" + SCHEMA + ".v_<entity>, always lower-case), spelled the way "
                + "the hub created it (letters, digits and underscores — a space is a typo): " + table);
        return table;
    }

    /**
     * Does this name look like what the hub COMPOSES under that prefix? Each clause mirrors one step
     * of a real composition, which is why none of them is a grammar of our invention:
     * {@code isPlainIdentifier} is the predicate ingestion gates its target table with BEFORE
     * composing {@code "t_" + name} (SourceBlockValidator, KafkaConnectorValidator; the CSV door
     * slugs), and {@link #FUSED_VIEW_NAME_RX} is the entity parser's alphabet after
     * {@code EntityViewCompiler}'s fold.
     *
     * <p>It tests the COMPONENT, never the composed token: {@code isPlainIdentifier("t_1foo")} is
     * true while {@code isPlainIdentifier("1foo")} is false, so validating the whole thing would
     * admit a table no ingest can create. And the two prefixes do NOT share an alphabet — an entity
     * may be named {@code 1customer}, so {@code connector.v_1customer} is real where
     * {@code connector.t_1foo} is not. Likewise {@code t_Orders} exists (a connector's target table
     * is never folded) while {@code v_Customer} cannot. Each accepted typo would come back as
     * {@code tableMissing}, the flag the page phrases as the legitimate "declared before ingesting"
     * case — the wrong errand.
     */
    private static boolean composedAs(String table, String prefix) {
        if (!table.startsWith(prefix) || table.length() == prefix.length()) return false;
        String name = table.substring(prefix.length());          // the COMPONENT, not the composed token
        return FUSED_VIEW_PREFIX.equals(prefix)
            ? name.matches(FUSED_VIEW_NAME_RX)
            : SqlIdentifiers.isPlainIdentifier(name);
    }

    /** A name that names nothing is the one column-shaped refusal left — everything else is a name.
     *  Worded for both callers: {@code column} is required, {@code key} is optional but, once
     *  written, must say something. */
    private static String requireNamed(String name, String path) {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException(path + " must name a column (got: " + name + ")");
        if (name.indexOf('\0') >= 0)
            throw new IllegalArgumentException(path + " contains a NUL, which no PostgreSQL "
                + "identifier can hold — the column it names cannot exist, now or ever. It is "
                + "invisible in the manifest: look for a \\0 escape in a double-quoted scalar");
        return name;
    }

    /**
     * Dormancy is the WATCHED half of a workflow, and not every watchable target can carry it.
     *
     * <p>A fused entity view cannot: the sweep is per-document, picking a key's latest version with
     * {@code ORDER BY key, _ingested_at DESC, id DESC} under {@code tenant_id = ?}, and an
     * {@code EntityViewCompiler} view projects the canonical columns plus {@code _conflicts} — none
     * of those three. Declared anyway, it applies with a clean 200 and then fails 42703 on every
     * tick inside the sweep's per-workflow net: an alert that can never arrive, and nothing for the
     * integrator to look at. Unlike {@code tableMissing} or {@code keyColumnMissing}, the condition
     * can never resolve — it is what a view IS — so this is a refusal, not a report.
     *
     * <p>It is checked BEFORE the key: sending someone to add {@code spec.on.key} when no key can
     * help is the wrong errand. (An entity manifest could in principle map a canonical column
     * literally named {@code _ingested_at} — {@code RESERVED} only holds {@code _conflicts},
     * {@code _src}, {@code _ord} — but all three would have to be mapped, as text, for the ordering
     * to mean anything. Not a use case; the refusal says what it wants instead.)
     */
    private static WorkflowManifest.DormancyDef optionalDormancy(JsonNode spec, Watched watched) {
        JsonNode dorm = spec.path("dormancy");
        if (dorm.isMissingNode() || dorm.isNull()) return null;
        if (watched.table().startsWith(FUSED_VIEW_PREFIX))
            throw new IllegalArgumentException("spec.dormancy cannot be declared on a fused entity "
                + "view (" + watched.table() + "): the sweep orders a document's versions by their "
                + "ingest, and a view carries none. Declare it on the ingest table the entity fuses, "
                + "or drop spec.dormancy and keep the taxonomy");
        String key = watched.key();
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
            root = SURFACE.readTree(yaml);
        } catch (Exception e) {
            return List.of();
        }
        return unknownKeys(root);
    }

    public List<String> unknownKeys(JsonNode root) {
        return SURFACE.unknownKeys(root);
    }

    public static String honoredSurface() {
        return SURFACE.honoredSurface();
    }
}
