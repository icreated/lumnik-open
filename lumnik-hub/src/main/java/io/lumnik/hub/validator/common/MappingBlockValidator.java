package io.lumnik.hub.validator.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import io.lumnik.hub.connector.mapping.MapColumnsRules;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.connector.mapping.hooks.ColumnEffect;
import io.lumnik.hub.connector.mapping.hooks.HookCatalog;
import io.lumnik.hub.connector.mapping.hooks.HookDescriptor.Tier;
import io.lumnik.hub.connector.mapping.hooks.UnknownHookParameterException;
import io.lumnik.hub.connector.mapping.spec.ErrorPolicy;
import io.lumnik.hub.connector.mapping.spec.HookInvocation;
import io.lumnik.hub.validator.ValidationError;
import io.lumnik.hub.validator.ValidationError.Severity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The checks of a {@code mapping} block, wherever it sits (#1617): a csv-file connector's {@code spec.mapping}, a
 * rest-generic endpoint's {@code mapping}. Every error is named under {@code at}, the block's own path.
 */
@ApplicationScoped
public class MappingBlockValidator {

    @Inject HookCatalog catalog;

    /** The checks that need nothing but the block: its keys, its hooks and their parameters, their composition. */
    public List<ValidationError> validate(JsonNode mapping, String at) {
        List<ValidationError> errs = new ArrayList<>();
        errs.addAll(validateRowAfterSplitRowRefused(mapping, at));
        errs.addAll(mapColumnsRefused(mapping, at));
        errs.addAll(unknownMappingKeys(mapping, at));
        errs.addAll(hooksThatCannotRun(mapping, at));
        errs.addAll(onErrorThatCannotBeRead(mapping, at));
        errs.addAll(fillDownRefusals(mapping, at));
        errs.addAll(regexExtractOverwrites(mapping, at));
        return errs;
    }

    /**
     * A hook this connector type cannot run, refused at each place the block declares it: {@code at.tier[i].hook}.
     * Each type says why — the reason is its own (csv's chunks, a REST endpoint's one row per item).
     */
    public static List<ValidationError> hookRefused(JsonNode mapping, String at, String tier, String hook,
                                                    String why, String instead) {
        JsonNode hooks = hooksOf(mapping, tier);
        List<ValidationError> errs = new ArrayList<>();
        for (int i = 0; i < hooks.size(); i++) {
            if (hooks.get(i).has(hook)) {
                errs.add(new ValidationError(at + "." + tier + "[" + i + "]." + hook, Severity.ERROR, why,
                    List.of(instead)));
            }
        }
        return errs;
    }

    /** The keys the run reads under a {@code mapping}, in the order a mapping runs them. */
    private static final List<String> MAPPING_KEYS = List.of("dataset", "row", "cell", "on-error");

    /** #1514: the run reads only {@link #MAPPING_KEYS} — a misspelt one ({@code cel:}) was ignored without a word. */
    private static List<ValidationError> unknownMappingKeys(JsonNode mapping, String at) {
        List<ValidationError> errs = new ArrayList<>();
        mapping.fieldNames().forEachRemaining(key -> {
            if (!MAPPING_KEYS.contains(key)) {
                errs.add(new ValidationError(at + "." + key, Severity.ERROR,
                    "mapping: unknown key '" + key + "' — accepted: " + String.join(", ", MAPPING_KEYS),
                    List.of("Check the spelling: the run reads nothing else under mapping")));
            }
        });
        return errs;
    }

    /** Row hooks a door of their own answers for: map-columns reports every violation at once, aggregator is refused whole. */
    private static final List<String> ROW_HOOKS_WITH_THEIR_OWN_DOOR = List.of("map-columns", "aggregator");

    /**
     * #1403: every hook is looked up and its parameters checked here, through the check the run makes before its first
     * row. Until then `lm apply` said ✓ to an unknown hook, a malformed entry or a parameter the hook refuses, and the
     * run failed before its first row. (#1402 did it for the Kotlin script alone.)
     */
    private List<ValidationError> hooksThatCannotRun(JsonNode mapping, String at) {
        List<ValidationError> errs = new ArrayList<>();
        for (Tier tier : Tier.values()) {
            String name = tier.name().toLowerCase(Locale.ROOT);
            JsonNode hooks;
            try {
                hooks = HookInvocation.requireList(mapping, name);
            } catch (MappingValidationException e) {
                errs.add(new ValidationError(at + "." + name, Severity.ERROR, e.getMessage(),
                    List.of("Write the tier as a list, one hook per entry")));
                continue;
            }
            for (int i = 0; i < hooks.size(); i++) {
                refusalOf(at, tier, name, i, hooks.get(i)).ifPresent(errs::add);
            }
        }
        return errs;
    }

    private Optional<ValidationError> refusalOf(String mappingAt, Tier tier, String name, int i, JsonNode entry) {
        String at = mappingAt + "." + name + "[" + i + "]";
        HookInvocation hook;
        try {
            hook = HookInvocation.parse(entry, name, i);
        } catch (MappingValidationException e) {
            return Optional.of(new ValidationError(at, Severity.ERROR, e.getMessage(),
                List.of("Write one hook per entry: - upper: { columns: [name] }")));
        }
        try {
            // Their own door answers for their values; which keys they take is the catalog's, as for every hook (#1487).
            if (tier == Tier.ROW && ROW_HOOKS_WITH_THEIR_OWN_DOOR.contains(hook.name())) {
                catalog.refuseUnknownParams(tier, hook.name(), hook.params());
            } else {
                catalog.validate(tier, hook.name(), hook.params());
            }
            return Optional.empty();
        } catch (MappingValidationException e) {
            List<String> known = catalog.names(tier);
            String recommended = !known.contains(hook.name())
                ? "The " + name + " hooks: " + String.join(", ", known)
                : recommendation(hook);
            String key = e instanceof UnknownHookParameterException unknown ? "." + unknown.key() : "";
            return Optional.of(new ValidationError(at + "." + hook.name() + key, Severity.ERROR, e.getMessage(),
                List.of(recommended)));
        }
    }

    private static String recommendation(HookInvocation hook) {
        if (!"kotlin".equals(hook.name())) return "The Transformers page lists " + hook.name() + "'s parameters";
        return hook.params().path("script").asText().startsWith("hooks:")
            ? "`lm hook scripts` lists the scripts of this hub's hooks directory"
            : "classpath: names a script built into the hub; hooks:<name>.kts names one your operator provides";
    }

    /**
     * #1408: the run reads {@code on-error}; a mode it would not know is refused here, before the first row. So is
     * {@code dlq} beside {@code split-row}, for the reason {@link #validateRowAfterSplitRowRefused} gives: it would
     * dead-letter fanned fragments one by one, more drops than source rows, and the write refuses that chunk.
     */
    private static List<ValidationError> onErrorThatCannotBeRead(JsonNode mapping, String at) {
        try {
            if (!ErrorPolicy.parse(mapping.path("on-error")).deadLetters()) return List.of();
            JsonNode rowHooks = hooksOf(mapping, "row");
            for (int i = 0; i < rowHooks.size(); i++) {
                if (rowHooks.get(i).has("split-row")) {
                    return List.of(new ValidationError(at + ".on-error", Severity.ERROR,
                        "on-error: dlq with split-row is not supported — it would dead-letter fanned fragments, "
                            + "not source rows, and the run would stop on it",
                        List.of("use soft or fail, or drop the split-row hook")));
                }
            }
            return List.of();
        } catch (MappingValidationException e) {
            return List.of(new ValidationError(at + ".on-error", Severity.ERROR, e.getMessage(),
                List.of("soft (the default) writes the row with the cell emptied, dlq sends the row to the "
                    + "dead letters as it was read, fail stops the run")));
        }
    }

    /** The declared hooks of one tier, or nothing — four refusals below start with this same question. */
    public static JsonNode hooksOf(JsonNode mapping, String tier) {
        JsonNode hooks = mapping.path(tier);
        return hooks.isArray() ? hooks : MissingNode.getInstance();
    }

    /**
     * fill-down's rules across hooks (#1452) — its own parameters are the hook's ({@code hooksThatCannotRun}), and a
     * column it reads that does not exist is {@link #columnsThatDoNotExist}'s:
     * <ul>
     *   <li>beside {@code sort-by}: it follows file order, and a sort reorders each chunk;</li>
     *   <li>a column carried by two declarations: two states would race on one cell.</li>
     * </ul>
     */
    private static List<ValidationError> fillDownRefusals(JsonNode mapping, String at) {
        List<ValidationError> errs = new ArrayList<>();
        JsonNode cell = hooksOf(mapping, "cell");
        boolean sorted = false;
        for (JsonNode hook : hooksOf(mapping, "dataset")) sorted |= hook.has("sort-by");
        Map<String, Integer> carriedBy = new HashMap<>();
        for (int i = 0; i < cell.size(); i++) {
            JsonNode fillDown = cell.get(i).path("fill-down");
            if (!fillDown.isObject()) continue;
            String base = at + ".cell[" + i + "].fill-down";
            if (sorted) {
                errs.add(new ValidationError(base, Severity.ERROR,
                    "fill-down follows the file's order, and sort-by reorders each chunk — the order it carries "
                        + "values along is lost", List.of("Drop sort-by, or sort the file before it is dropped")));
            }
            JsonNode columns = fillDown.path("columns");
            if (!columns.isArray()) continue;                    // the hook's own refusal, not a crash here
            for (int j = 0; j < columns.size(); j++) {
                String column = columns.get(j).asText();
                Integer earlier = carriedBy.putIfAbsent(column, i);
                if (earlier != null && earlier != i) {
                    errs.add(new ValidationError(base + ".columns[" + j + "]", Severity.ERROR,
                        "column '" + column + "' is already carried by cell[" + earlier + "].fill-down — two "
                            + "declarations would race on one cell", List.of("Carry each column in one fill-down")));
                }
            }
        }
        return errs;
    }

    /**
     * regex-extract writes every group's column on every row — null where it misses — so a column it writes must be
     * its own. A column an earlier regex-extract writes (#1504) is refused here by the group's path, naming the first,
     * whatever the source's columns. The column it reads is the hook's own refusal; one the file or another hook
     * brings is {@link #columnsThatDoNotExist}'s, where the columns are known — the run refuses it otherwise (#1505).
     */
    private static List<ValidationError> regexExtractOverwrites(JsonNode mapping, String at) {
        List<ValidationError> errs = new ArrayList<>();
        JsonNode cell = hooksOf(mapping, "cell");
        Map<String, Integer> writtenBy = new HashMap<>();
        for (int i = 0; i < cell.size(); i++) {
            JsonNode groups = cell.get(i).path("regex-extract").path("groups");
            for (Iterator<Map.Entry<String, JsonNode>> it = groups.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> g = it.next();
                String column = g.getValue().asText();
                Integer earlier = writtenBy.putIfAbsent(column, i);
                if (earlier != null && earlier != i) {
                    errs.add(new ValidationError(at + ".cell[" + i + "].regex-extract.groups." + g.getKey(),
                        Severity.ERROR, "column '" + column + "' is already written by cell[" + earlier + "].regex-extract"
                            + " — this one writes null on every row it does not match, erasing that value",
                        List.of("Read both shapes with one pattern, an alternation: ^(?:A|B)(…)",
                            "Or write this group to a column of its own")));
                }
            }
        }
        return errs;
    }

    /**
     * #1500: a hook pointed at a column that does not exist validated clean, then did nothing — and filter-where dropped
     * every row. When the source's columns are known ({@code source}: a csv file's {@code line} or declared
     * {@code columns}, a REST endpoint's promoted columns), the mapping is walked in run order, each hook's {@link ColumnEffect} applied to the set
     * of columns: a column it reads that is not there at that point is refused, by the parameter's path. The columns
     * come from the hooks' own declarations, never from a table kept here. After a Kotlin script, which may change any
     * key, nothing is known and nothing is said; with columns known only at run time ({@code source} null), nothing
     * is said either. {@code readersAtTheEnd} (path → column) are what reads the row the mapping ends with.
     */
    public List<ValidationError> columnsThatDoNotExist(JsonNode mapping, String mappingAt, Set<String> source,
                                                       Map<String, String> readersAtTheEnd) {
        return columnsThatDoNotExist(mapping, mappingAt, source, readersAtTheEnd, null);
    }

    /**
     * The same walk, where the row may only hold {@code declared} columns (a REST endpoint's promote entries, #1617): a
     * hook that adds another is refused by its own path. {@code declared} null: any column may be added (csv).
     */
    public List<ValidationError> columnsThatDoNotExist(JsonNode mapping, String mappingAt, Set<String> source,
                                                       Map<String, String> readersAtTheEnd, Set<String> declared) {
        if (source == null) return List.of();
        Set<String> columns = new HashSet<>(source);
        List<ValidationError> errs = new ArrayList<>();
        Set<String> regexWritten = new HashSet<>();   // a second writer is regexExtractOverwrites' to name
        for (Tier tier : Tier.values()) {
            String name = tier.name().toLowerCase(Locale.ROOT);
            JsonNode hooks = hooksOf(mapping, name);
            for (int i = 0; i < hooks.size(); i++) {
                HookInvocation hook;
                try {
                    hook = HookInvocation.parse(hooks.get(i), name, i);
                } catch (MappingValidationException malformed) {
                    continue;                                            // hooksThatCannotRun names it
                }
                ColumnEffect effect = catalog.columns(tier, hook.name(), hook.params());
                if (effect == null) continue;                            // an unknown hook: refused by name elsewhere
                String at = mappingAt + "." + name + "[" + i + "]." + hook.name() + ".";
                if (hook.name().equals("regex-extract")) {
                    String read = hook.params().path("column").asText();
                    for (Iterator<Map.Entry<String, JsonNode>> it = hook.params().path("groups").fields(); it.hasNext(); ) {
                        Map.Entry<String, JsonNode> g = it.next();
                        String column = g.getValue().asText();
                        if (columns.contains(column) && !column.equals(read) && !regexWritten.contains(column)) {
                            errs.add(new ValidationError(at + "groups." + g.getKey(), Severity.ERROR, "column '" + column
                                + "' is already in the row here — the file or an earlier hook writes it; this hook would "
                                + "write null over it on every row it does not match",
                                List.of("Write the group to a column of its own")));
                        }
                        regexWritten.add(column);
                    }
                }
                for (ColumnEffect.Step step : effect.steps()) {   // in the hook's own order
                    switch (step) {
                        case ColumnEffect.Read read -> {
                            if (!columns.contains(read.column())) errs.add(missing(at + read.path(), read.column()));
                        }
                        case ColumnEffect.AnyOf any -> {
                            if (any.columns().stream().noneMatch(columns::contains)) {
                                errs.add(new ValidationError(at + any.path(), Severity.ERROR, "none of "
                                    + any.columns().stream().map(c -> "'" + c + "'").toList() + " is in the row here — "
                                    + "the source's columns are known and no earlier hook adds one; the hook would do "
                                    + "nothing", List.of("Check the spelling, or add one of them in an earlier hook")));
                            }
                        }
                        case ColumnEffect.Remove remove -> columns.remove(remove.column());
                        case ColumnEffect.Add add -> {
                            if (declared != null && !declared.contains(add.column())) {
                                errs.add(new ValidationError(at.substring(0, at.length() - 1), Severity.ERROR,
                                    "this hook writes column '" + add.column() + "', which no promote entry declares — "
                                        + "the run would refuse it", List.of("Declare it in target.promote with its "
                                        + "type, without a path: { name: " + add.column() + ", type: text }")));
                            }
                            columns.add(add.column());
                        }
                    }
                }
                if (effect.unknownAfter()) return errs;
            }
        }
        // The readers of the row the mapping ends with: on-error's columns, and the caller's own (path → column).
        mapping.path("on-error").path("fields").fieldNames().forEachRemaining(column -> {
            if (!columns.contains(column)) errs.add(missing(mappingAt + ".on-error.fields." + column, column));
        });
        readersAtTheEnd.forEach((path, column) -> {
            if (!columns.contains(column)) errs.add(missing(path, column));
        });
        return errs;
    }

    private static ValidationError missing(String path, String column) {
        return new ValidationError(path, Severity.ERROR, "column '" + column + "' is not in the row here — the source's "
            + "columns are known and no earlier hook adds it; the hook would do nothing with it",
            List.of("Check the spelling, or add the column in an earlier hook"));
    }

    /**
     * The csv door for map-columns' own rules — the one that did not exist. jdbc had two doors on
     * these rules and csv had none, which is backwards: jdbc's rename is a narrow config key the
     * hub assembles, csv's is written BY HAND in spec.mapping. A bad rename was accepted by
     * `lm apply`, scheduled, and killed the run on its first row.
     *
     * <p>The rule is {@code MapColumnsRules}, shared with both jdbc doors and with the hook. This
     * one accumulates, so an integrator sees every problem in the block at once rather than the
     * first — which is the whole point of refusing before the run instead of during it. The hook
     * still refuses at run time as a backstop: the template mapping path reaches it without
     * passing any connector validator.
     */
    private static List<ValidationError> mapColumnsRefused(JsonNode mapping, String at) {
        JsonNode rowHooks = hooksOf(mapping, "row");
        List<ValidationError> errs = new ArrayList<>();
        for (int i = 0; i < rowHooks.size(); i++) {
            JsonNode params = rowHooks.get(i).path("map-columns");
            if (params.isMissingNode()) continue;
            String base = at + ".row[" + i + "].map-columns";
            List<MapColumnsRules.Violation> violations = new ArrayList<>();
            violations.addAll(MapColumnsRules.checkRename(params.path("rename")));
            violations.addAll(MapColumnsRules.checkDrop(params.path("drop")));
            for (MapColumnsRules.Violation v : violations) {
                // The path goes in the path field; lm prints it on its own line above the
                // message, so repeating it in the text reads as a stutter.
                errs.add(new ValidationError(base + "." + v.path(), Severity.ERROR, v.reason(),
                    List.of("Example: map-columns: { rename: { CUST_NM: name }, drop: [flag] }")));
            }
        }
        return errs;
    }

    /**
     * split-row fans one source row into N fragments; a validate-row placed after it would drop
     * failing fragments one at a time instead of once per source row, so the mapping tier can
     * record more drops than source rows — corrupting {@code records_out} into a negative count
     * (mapping-drop-accounting design, "Composition rule"). The engine also refuses this
     * defensively (belt-and-braces for hooks the validator doesn't know about), but refusing here
     * catches it at validate/apply time instead of mid-run.
     */
    private static List<ValidationError> validateRowAfterSplitRowRefused(JsonNode mapping, String at) {
        JsonNode rowHooks = hooksOf(mapping, "row");
        int splitAt = -1;
        for (int i = 0; i < rowHooks.size(); i++) {
            if (rowHooks.get(i).has("split-row")) {
                splitAt = i;
                break;
            }
        }
        if (splitAt < 0) return List.of();
        List<ValidationError> errs = new ArrayList<>();
        for (int i = splitAt + 1; i < rowHooks.size(); i++) {
            if (rowHooks.get(i).has("validate-row")) {
                errs.add(new ValidationError(at + ".row[" + i + "].validate-row",
                    Severity.ERROR,
                    "validate-row after split-row is not supported — validation would drop "
                        + "fanned fragments and corrupt the run arithmetic. Validate before "
                        + "splitting.",
                    List.of("Validate before splitting, or drop the validate-row hook")));
            }
        }
        return errs;
    }
}
