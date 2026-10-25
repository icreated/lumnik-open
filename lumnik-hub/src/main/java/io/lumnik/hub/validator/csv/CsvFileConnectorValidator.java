package io.lumnik.hub.validator.csv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import io.lumnik.hub.connector.csv.CsvFileReader;
import io.lumnik.hub.connector.csv.CsvFullRefresh;
import io.lumnik.hub.connector.mapping.MapColumnsRules;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.connector.mapping.hooks.ColumnEffect;
import io.lumnik.hub.connector.mapping.hooks.HookCatalog;
import io.lumnik.hub.connector.mapping.hooks.HookDescriptor.Tier;
import io.lumnik.hub.connector.mapping.hooks.UnknownHookParameterException;
import io.lumnik.hub.connector.mapping.spec.ErrorPolicy;
import io.lumnik.hub.connector.mapping.spec.HookInvocation;
import io.lumnik.hub.connector.registry.ConnectorTypeId;
import io.lumnik.hub.validator.ConfigDef;
import io.lumnik.hub.validator.JsonNodes;
import io.lumnik.hub.validator.ConfigDef.Importance;
import io.lumnik.hub.validator.ConfigDef.Type;
import io.lumnik.hub.validator.ConnectorValidator;
import io.lumnik.hub.validator.ValidationError;
import io.lumnik.hub.validator.ValidationError.Severity;
import io.lumnik.hub.validator.common.CronField;
import io.lumnik.hub.validator.common.TargetBlockValidator;
import io.lumnik.hub.validator.common.WindowField;
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

@ApplicationScoped
@ConnectorTypeId("csv-file")
public class CsvFileConnectorValidator implements ConnectorValidator {

    private static final String TARGET = "target";

    @Inject HookCatalog catalog;

    @Override public String kind() { return "csv-file"; }

    @Override
    public ConfigDef configDef() {
        return new ConfigDef()
            .requireNested("transport", Importance.HIGH, "Transport block",
                TransportBlockValidator.configDef())
            // Optional, for target's reason. Every parser key either carries a default or is needed
            // only in a case the defaults rule out: delimiter and encoding are sniffed per file when
            // absent, has_header and bom_strip default true, null_values is seeded — and `columns`,
            // the one key with no default, is required solely when has_header is false, which its own
            // default of true excludes. So the block could only ever be written to say nothing (#543).
            .optionalNested("parser", Importance.HIGH, "CSV parser config",
                ParserBlockValidator.configDef())
            .optional("schedule", Type.STRING, Importance.MEDIUM,
                "Cron expression for recurring ingestion", null)
            .requireEnum("after_process", Importance.MEDIUM,
                "What to do after a file is processed",
                "archive", "delete", "move", "none")
            .optional("archive_path", Type.STRING, Importance.LOW,
                "Destination for `move` action", null)
            // Two keys the root of a csv spec really carries, declared so closing does not accuse
            // them. `mapping` is consumed by CsvFileConnectorType.buildSpec (and shipped manifests carry it); its
            // keys are refused by unknownMappingKeys, its hooks by hooksThatCannotRun.
            // `window` is read by the SHARED apply preamble (ApplyUpsert); declared and checked
            // through WindowField, which owns both wordings for all four connector types.
            .optional("mapping", Type.NESTED, Importance.MEDIUM,
                "Dataset/row/cell transformer hooks — see the transformers page", null)
            .optional("window", Type.STRING, Importance.MEDIUM, WindowField.DOC, null)
            // #1195: one local file is the table, and a run that read it whole removes what it no
            // longer holds — at most this share of the table, unless lifted.
            .optionalNested("refresh", Importance.MEDIUM, "Full refresh of one local file: the removal guard",
                new ConfigDef().optionalCustom("max_removed", Type.STRING, Importance.MEDIUM,
                    "Largest share of the table one run may remove: a percentage (30% by default), or off",
                    CsvFullRefresh::maxRemovedProblem).closed())
            // Optional: its only key (`indexes`) is optional too, so requiring the block made every
            // csv manifest carry an empty `target: {}` that the runtime reads nothing from (#543).
            .optionalNested(TARGET, Importance.HIGH, "Target persistence",
                TargetBlockValidator.configDef())
            .closed();
    }

    /**
     * The cross-field catalogue. Each rule below is independent and self-contained — this method's
     * job is to name them in order, not to hold them. (S3776: the complexity was never tangled, it
     * was a list; extracted so the list reads as one.)
     */
    @Override
    public List<ValidationError> validateCrossField(JsonNode spec) {
        JsonNode tr = spec.path("transport");
        String tKind = tr.path("kind").asText();
        String afterProc = spec.path("after_process").asText();

        List<ValidationError> errs = new ArrayList<>();
        errs.addAll(transportPathRequired(tr, tKind));
        errs.addAll(sftpNeedsACredential(tr, tKind));
        errs.addAll(sftpNeedsAFingerprint(tr, tKind));
        errs.addAll(moveNeedsAnArchivePath(spec, afterProc));
        errs.addAll(localCannotRelocate(tKind, afterProc));
        errs.addAll(oneShotOnlyTransportKeys(tr));
        errs.addAll(headerlessNeedsColumns(spec));
        errs.addAll(linesConflicts(spec));
        errs.addAll(scheduleCronIsValid(spec));
        WindowField.check(spec.path("window"), errs);
        errs.addAll(TargetBlockValidator.validateIndexes(
            spec.path(TARGET).path("indexes"), "spec.target.indexes"));
        errs.addAll(aggregatorRefused(spec));
        errs.addAll(validateRowAfterSplitRowRefused(spec));
        errs.addAll(mapColumnsRefused(spec));
        errs.addAll(refreshWithoutAState(spec));
        errs.addAll(unknownMappingKeys(spec));
        errs.addAll(hooksThatCannotRun(spec));
        errs.addAll(onErrorThatCannotBeRead(spec));
        errs.addAll(fillDownRefusals(spec));
        errs.addAll(regexExtractOverwrites(spec));
        errs.addAll(columnsThatDoNotExist(spec));
        return errs;
    }

    /** The keys the run reads under {@code spec.mapping}, in the order a mapping runs them. */
    private static final List<String> MAPPING_KEYS = List.of("dataset", "row", "cell", "on-error");

    /** #1514: the run reads only {@link #MAPPING_KEYS} — a misspelt one ({@code cel:}) was ignored without a word. */
    private static List<ValidationError> unknownMappingKeys(JsonNode spec) {
        List<ValidationError> errs = new ArrayList<>();
        spec.path("mapping").fieldNames().forEachRemaining(key -> {
            if (!MAPPING_KEYS.contains(key)) {
                errs.add(new ValidationError("spec.mapping." + key, Severity.ERROR,
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
    private List<ValidationError> hooksThatCannotRun(JsonNode spec) {
        List<ValidationError> errs = new ArrayList<>();
        for (Tier tier : Tier.values()) {
            String name = tier.name().toLowerCase(Locale.ROOT);
            JsonNode hooks;
            try {
                hooks = HookInvocation.requireList(spec.path("mapping"), name);
            } catch (MappingValidationException e) {
                errs.add(new ValidationError("spec.mapping." + name, Severity.ERROR, e.getMessage(),
                    List.of("Write the tier as a list, one hook per entry")));
                continue;
            }
            for (int i = 0; i < hooks.size(); i++) {
                refusalOf(tier, name, i, hooks.get(i)).ifPresent(errs::add);
            }
        }
        return errs;
    }

    private Optional<ValidationError> refusalOf(Tier tier, String name, int i, JsonNode entry) {
        String at = "spec.mapping." + name + "[" + i + "]";
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
    private static List<ValidationError> onErrorThatCannotBeRead(JsonNode spec) {
        try {
            if (!ErrorPolicy.parse(spec.path("mapping").path("on-error")).deadLetters()) return List.of();
            JsonNode rowHooks = hooksOf(spec, "row");
            for (int i = 0; i < rowHooks.size(); i++) {
                if (rowHooks.get(i).has("split-row")) {
                    return List.of(new ValidationError("spec.mapping.on-error", Severity.ERROR,
                        "on-error: dlq with split-row is not supported — it would dead-letter fanned fragments, "
                            + "not source rows, and the run would stop on it",
                        List.of("use soft or fail, or drop the split-row hook")));
                }
            }
            return List.of();
        } catch (MappingValidationException e) {
            return List.of(new ValidationError("spec.mapping.on-error", Severity.ERROR, e.getMessage(),
                List.of("soft (the default) writes the row with the cell emptied, dlq sends the row to the "
                    + "dead letters as it was read, fail stops the run")));
        }
    }

    /**
     * {@code refresh} only means something where the source is a state: one local file. A glob is a
     * drop folder and a remote transport keeps a watermark over the files it took — both only ever
     * add rows, so the block would be read by nothing. A warning, not a refusal: the manifest is not
     * wrong, it says something this source cannot do.
     */
    private static List<ValidationError> refreshWithoutAState(JsonNode spec) {
        if (spec.path("refresh").isMissingNode()) return List.of();
        JsonNode tr = spec.path("transport");
        // The runtime's default for an absent kind (resolveTransport), so the two never disagree.
        if (CsvFullRefresh.isOneLocalFile(tr.path("kind").asText("local"), tr.path("path").asText(""))) return List.of();
        return List.of(new ValidationError("spec.refresh", Severity.WARNING,
            "refresh is read only for one local file (transport kind: local, a path without * or ?): "
                + "this source is a drop folder and never removes rows, so the block does nothing here",
            List.of("Remove the refresh block, or point transport.path at the one file that holds the table")));
    }

    private static List<ValidationError> transportPathRequired(JsonNode tr, String tKind) {
        JsonNode pathNode = tr.path("path");
        boolean pathBlank = pathNode.isMissingNode() || pathNode.isNull()
                || pathNode.asText().isBlank();
        if (!("local".equals(tKind) || "sftp".equals(tKind)) || !pathBlank) return List.of();
        return List.of(new ValidationError("spec.transport.path", Severity.ERROR,
            "path is required for local/sftp transport",
            List.of("Set path: /imports/*.csv (local) or a remote glob (sftp)")));
    }

    private static List<ValidationError> sftpNeedsACredential(JsonNode tr, String tKind) {
        if (!"sftp".equals(tKind)) return List.of();
        // nonBlank, not isMissingNode: these name a SECRET, and `key_env:` written empty — or
        // written blank — names none. Asking presence let a NullNode satisfy the either-or, so the
        // manifest applied and the run died reaching for a secret nobody had named: at run time,
        // which is the tier this apply-time refusal exists to move the failure off (#552).
        boolean hasKey = JsonNodes.nonBlank(tr.path("key_env"));
        boolean hasPwd = JsonNodes.nonBlank(tr.path("password_env"));
        if (hasKey || hasPwd) return List.of();
        return List.of(new ValidationError("spec.transport", Severity.ERROR,
            "SFTP transport requires key_env or password_env",
            List.of("Use `lm secret set <NAME> ...` then reference via key_env: <NAME>")));
    }

    /** strict_host_key (default true) refuses at every run a server whose key nothing declares (#1251). */
    private static List<ValidationError> sftpNeedsAFingerprint(JsonNode tr, String tKind) {
        if (!"sftp".equals(tKind) || !tr.path("strict_host_key").asBoolean(true)
            || JsonNodes.nonBlank(tr.path("host_key_fingerprint"))) return List.of();
        return List.of(new ValidationError("spec.transport.host_key_fingerprint", Severity.ERROR,
            "SFTP transport with strict_host_key (the default) needs host_key_fingerprint — every run would refuse to connect",
            List.of("Read it with `ssh-keyscan -p " + tr.path("port").asInt(22) + " " + tr.path("host").asText("HOST")
                + " | ssh-keygen -lf -` (the run's refusal prints the same), confirm it out of band, "
                + "then set host_key_fingerprint: \"SHA256:…\"")));
    }

    private static List<ValidationError> moveNeedsAnArchivePath(JsonNode spec, String afterProc) {
        // blankOrNull, not isMissingNode: a key written with nothing after it is a NullNode —
        // present by that one question, absent by every other, including ConfigDefRunner's. It
        // validated green and the apply then stored the literal string "null" as the path, so the
        // files were moved into a directory named that. The helper exists precisely for this trap.
        if (!"move".equals(afterProc) || !JsonNodes.blankOrNull(spec.path("archive_path"))) return List.of();
        return List.of(new ValidationError("spec.archive_path", Severity.ERROR,
            "archive_path is required when after_process=move",
            List.of("Set archive_path: /path/to/archive/")));
    }

    /**
     * The local scheduled reader (row-level glob cursor) has no per-file completion tracking:
     * archive/delete/move never execute there — files would silently stay in place. Remote
     * transports honor it; support waits for a per-file local cursor.
     */
    private static List<ValidationError> localCannotRelocate(String tKind, String afterProc) {
        if (!"local".equals(tKind) || !List.of("archive", "delete", "move").contains(afterProc)) {
            return List.of();
        }
        return List.of(new ValidationError("spec.after_process", Severity.ERROR,
            "after_process: " + afterProc + " is not executed on local connector runs — "
                + "files stay in place (sftp and s3 connectors honor it)",
            List.of("Set after_process: none for a local connector, "
                + "or move the files with an sftp/s3 transport")));
    }

    /**
     * Transport hooks and max_file_size are read only by the one-shot ingest service, which no
     * {@code lm csv ingest} flag feeds; scheduled connector runs never invoke them — refuse rather
     * than silently ignore. The message names no gesture: there is none to point at.
     */
    private static List<ValidationError> oneShotOnlyTransportKeys(JsonNode tr) {
        List<ValidationError> errs = new ArrayList<>();
        for (String key : List.of("file_filter", "pre_process", "post_process", "max_file_size")) {
            // absent, not isMissingNode: this rule TEACHES about a key the reader declared, and
            // `pre_process:` with nothing after it declares nothing — refusing it taught about a
            // lever the manifest never pulled (#552).
            if (!JsonNodes.absent(tr.path(key))) {
                errs.add(new ValidationError("spec.transport." + key, Severity.ERROR,
                    key + " is not read by connector runs",
                    List.of("Drop it from the connector manifest")));
            }
        }
        return errs;
    }

    /** C5: headerless rows need names — the run used to fail where validate passed clean. */
    private static List<ValidationError> headerlessNeedsColumns(JsonNode spec) {
        JsonNode parser = spec.path("parser");
        boolean headerless = headerless(parser);
        boolean noColumns = parser.path("columns").isMissingNode()
                || parser.path("columns").isEmpty();
        // lines: true names its one column itself; linesConflicts answers for has_header beside it.
        if (!headerless || !noColumns || parser.path("lines").asBoolean(false)) return List.of();
        return List.of(new ValidationError("spec.parser.columns", Severity.ERROR,
            "columns is required when has_header: false — headerless rows need names",
            List.of("Add parser.columns: [code, name, ...] in file order")));
    }

    /**
     * #1415: {@code lines: true} reads one line as one row, one column, unsplit and unquoted — a delimiter, a header
     * or a second column would each say the file is something else.
     */
    private static List<ValidationError> linesConflicts(JsonNode spec) {
        JsonNode parser = spec.path("parser");
        if (!parser.path("lines").asBoolean(false)) {
            // The unit separator is how the reader knows line mode: declared by hand it would switch quoting off.
            return String.valueOf(CsvFileReader.Config.LINES).equals(parser.path("delimiter").asText(null))
                ? List.of(new ValidationError("spec.parser.delimiter", Severity.ERROR,
                    "the unit separator (\\u001F) is reserved for lines: true",
                    List.of("Use lines: true to read one line as one row, or another delimiter")))
                : List.of();
        }
        List<ValidationError> errs = new ArrayList<>();
        if (!JsonNodes.blankOrNull(parser.path("delimiter"))) {
            errs.add(linesRefuses("delimiter", "lines: true splits nothing — a line is one value"));
        }
        if (!JsonNodes.blankOrNull(parser.path("has_header"))) {
            errs.add(linesRefuses("has_header", "lines: true has no header — the first line is data, like every other"));
        }
        if (parser.path("columns").size() > 1) {
            errs.add(linesRefuses("columns", "lines: true gives one column — name it with columns: [<name>]"));
        }
        return errs;
    }

    private static ValidationError linesRefuses(String key, String message) {
        return new ValidationError("spec.parser." + key, Severity.ERROR, message,
            List.of("Remove spec.parser." + key + ", or drop lines: true to read the file as CSV"));
    }

    private static List<ValidationError> scheduleCronIsValid(JsonNode spec) {
        List<ValidationError> errs = new ArrayList<>();
        CronField.check(spec.path("schedule"), errs,
            "Example: schedule: \"0 6 * * *\" (daily at 06:00 UTC)");
        return errs;
    }

    /** The declared hooks of one tier, or nothing — four refusals below start with this same question. */
    private static JsonNode hooksOf(JsonNode spec, String tier) {
        JsonNode hooks = spec.path("mapping").path(tier);
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
    private static List<ValidationError> fillDownRefusals(JsonNode spec) {
        List<ValidationError> errs = new ArrayList<>();
        JsonNode cell = hooksOf(spec, "cell");
        boolean sorted = false;
        for (JsonNode hook : hooksOf(spec, "dataset")) sorted |= hook.has("sort-by");
        Map<String, Integer> carriedBy = new HashMap<>();
        for (int i = 0; i < cell.size(); i++) {
            JsonNode fillDown = cell.get(i).path("fill-down");
            if (!fillDown.isObject()) continue;
            String base = "spec.mapping.cell[" + i + "].fill-down";
            if (sorted) {
                errs.add(new ValidationError(base, Severity.ERROR,
                    "fill-down follows the file's order, and sort-by reorders each chunk — the order it carries "
                        + "values along is lost", List.of("Drop sort-by, or sort the file before it is dropped")));
            }
            JsonNode columns = fillDown.path("columns");
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
    private static List<ValidationError> regexExtractOverwrites(JsonNode spec) {
        List<ValidationError> errs = new ArrayList<>();
        JsonNode cell = hooksOf(spec, "cell");
        Map<String, Integer> writtenBy = new HashMap<>();
        for (int i = 0; i < cell.size(); i++) {
            JsonNode groups = cell.get(i).path("regex-extract").path("groups");
            for (Iterator<Map.Entry<String, JsonNode>> it = groups.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> g = it.next();
                String column = g.getValue().asText();
                Integer earlier = writtenBy.putIfAbsent(column, i);
                if (earlier != null && earlier != i) {
                    errs.add(new ValidationError("spec.mapping.cell[" + i + "].regex-extract.groups." + g.getKey(),
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
     * every row. When the source's columns are known ({@code parser.lines: true}: {@code line}; a headerless file: its
     * declared {@code columns}), the mapping is walked in run order, each hook's {@link ColumnEffect} applied to the set
     * of columns: a column it reads that is not there at that point is refused, by the parameter's path. The columns
     * come from the hooks' own declarations, never from a table kept here. After a Kotlin script, which may change any
     * key, nothing is known and nothing is said; with a header read at run time, nothing is said either.
     */
    private List<ValidationError> columnsThatDoNotExist(JsonNode spec) {
        Set<String> columns = sourceColumns(spec.path("parser"));
        if (columns == null) return List.of();
        List<ValidationError> errs = new ArrayList<>();
        Set<String> regexWritten = new HashSet<>();   // a second writer is regexExtractOverwrites' to name
        for (Tier tier : Tier.values()) {
            String name = tier.name().toLowerCase(Locale.ROOT);
            JsonNode hooks = hooksOf(spec, name);
            for (int i = 0; i < hooks.size(); i++) {
                HookInvocation hook;
                try {
                    hook = HookInvocation.parse(hooks.get(i), name, i);
                } catch (MappingValidationException malformed) {
                    continue;                                            // hooksThatCannotRun names it
                }
                ColumnEffect effect = catalog.columns(tier, hook.name(), hook.params());
                if (effect == null) continue;                            // an unknown hook: refused by name elsewhere
                String at = "spec.mapping." + name + "[" + i + "]." + hook.name() + ".";
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
                        case ColumnEffect.Add add -> columns.add(add.column());
                    }
                }
                if (effect.unknownAfter()) return errs;
            }
        }
        // The readers of the row the mapping ends with: on-error's columns, and the target's indexes.
        spec.path("mapping").path("on-error").path("fields").fieldNames().forEachRemaining(column -> {
            if (!columns.contains(column)) errs.add(missing("spec.mapping.on-error.fields." + column, column));
        });
        JsonNode indexes = spec.path(TARGET).path("indexes");
        for (int i = 0; i < indexes.size(); i++) {
            JsonNode indexed = indexes.get(i).path("columns");
            for (int j = 0; j < indexed.size(); j++) {
                String column = indexed.get(j).asText();
                if (!columns.contains(column)) {
                    errs.add(missing("spec.target.indexes[" + i + "].columns[" + j + "]", column));
                }
            }
        }
        return errs;
    }

    /** The columns a known source hands the mapping, or null when they are only known once the file is read. */
    private static Set<String> sourceColumns(JsonNode parser) {
        if (parser.path("lines").asBoolean(false)) return new HashSet<>(Set.of("line"));
        if (!headerless(parser) || !parser.path("columns").isArray() || parser.path("columns").isEmpty()) return null;
        Set<String> columns = new HashSet<>();
        parser.path("columns").forEach(c -> columns.add(c.asText()));
        return columns;
    }

    /** {@code has_header: false}: the parser names the columns itself, from {@code columns}. */
    private static boolean headerless(JsonNode parser) {
        return parser.path("has_header").isBoolean() && !parser.path("has_header").asBoolean();
    }

    private static ValidationError missing(String path, String column) {
        return new ValidationError(path, Severity.ERROR, "column '" + column + "' is not in the row here — the source's "
            + "columns are known and no earlier hook adds it; the hook would do nothing with it",
            List.of("Check the spelling, or add the column in an earlier hook"));
    }

    /**
     * Connector runs are chunked transactions with a fresh mapping state per chunk: the
     * aggregator's cross-chunk buffer would be silently dropped at every chunk boundary.
     *
     * <p>Dead knobs (tribunal B1/B2) chunk_size and table_prefix left their ConfigDefs and the
     * blocks are CLOSED (C3) — the generic unknown-key WARNING covers them, no duplicate here.
     */
    private static List<ValidationError> aggregatorRefused(JsonNode spec) {
        JsonNode rowHooks = hooksOf(spec, "row");
        List<ValidationError> errs = new ArrayList<>();
        for (int i = 0; i < rowHooks.size(); i++) {
            if (rowHooks.get(i).has("aggregator")) {
                errs.add(new ValidationError("spec.mapping.row[" + i + "].aggregator",
                    Severity.ERROR,
                    "aggregator is not supported on csv-file connector runs — each chunk "
                        + "commits separately, buffered rows would be silently lost at chunk "
                        + "boundaries",
                    List.of("Pre-aggregate in the source (e.g. a grouped export), "
                        + "or drop the aggregator hook")));
            }
        }
        return errs;
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
    private static List<ValidationError> mapColumnsRefused(JsonNode spec) {
        JsonNode rowHooks = hooksOf(spec, "row");
        List<ValidationError> errs = new ArrayList<>();
        for (int i = 0; i < rowHooks.size(); i++) {
            JsonNode params = rowHooks.get(i).path("map-columns");
            if (params.isMissingNode()) continue;
            String base = "spec.mapping.row[" + i + "].map-columns";
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
    private static List<ValidationError> validateRowAfterSplitRowRefused(JsonNode spec) {
        JsonNode rowHooks = hooksOf(spec, "row");
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
                errs.add(new ValidationError("spec.mapping.row[" + i + "].validate-row",
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
