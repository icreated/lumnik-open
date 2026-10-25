package io.lumnik.hub.connector.mapping.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.MappingValidationException;
import io.lumnik.hub.connector.mapping.ScalarParams;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.*;
import java.util.function.Consumer;

@ApplicationScoped
public class HookCatalog {

  /**
   * What {@code params()} answers for a hook whose keys are not knobs but the columns it fills ({@code defaults},
   * {@code enrich-context}): every key is accepted (#1487).
   */
  public static final Set<String> KEYS_ARE_COLUMNS = Set.of("*");

  @Inject Instance<DatasetHook> datasetHooks;
  @Inject Instance<RowHook>     rowHooks;
  @Inject Instance<CellHook>    cellHooks;

  private final Map<String, DatasetHook> datasetByName = new HashMap<>();
  private final Map<String, RowHook>     rowByName     = new HashMap<>();
  private final Map<String, CellHook>    cellByName    = new HashMap<>();

  @PostConstruct
  void init() {
    for (DatasetHook h : datasetHooks) {
      String name = hookName(h);
      if (name != null) datasetByName.put(name, h);
    }
    for (RowHook h : rowHooks) {
      String name = hookName(h);
      if (name != null) rowByName.put(name, h);
    }
    for (CellHook h : cellHooks) {
      String name = hookName(h);
      if (name != null) cellByName.put(name, h);
    }
  }

  private static String hookName(Object bean) {
    Class<?> c = bean.getClass();
    // CDI proxy: walk up to the real class
    while (c.getName().contains("_ClientProxy") || c.getName().contains("_Subclass")) {
      c = c.getSuperclass();
      if (c == Object.class || c == null) return null;
    }
    HookId ann = c.getAnnotation(HookId.class);
    return ann != null ? ann.value() : null;
  }

  public DatasetHook dataset(String name) { return datasetByName.get(name); }
  public RowHook     row    (String name) { return rowByName.get(name); }
  public CellHook    cell   (String name) { return cellByName.get(name); }

  /**
   * Refuses a hook this tier does not have, then a key the hook does not read (#1487), then lets the hook refuse its
   * parameters: what a run checks before its first row, and what a csv-file apply checks (#1403) — one owner, so the
   * two cannot disagree. The unknown key speaks first: a misspelt required key is a typo to name, not a key missing.
   */
  public void validate(HookDescriptor.Tier tier, String name, JsonNode params) {
    Consumer<JsonNode> check = switch (tier) {
      case DATASET -> { DatasetHook h = dataset(name); yield h == null ? null : h::validateParams; }
      case ROW -> { RowHook h = row(name); yield h == null ? null : h::validateParams; }
      case CELL -> { CellHook h = cell(name); yield h == null ? null : h::validateParams; }
    };
    if (check == null) throw unknownHook(tier, name);
    // #1487: a key the hook does not read speaks first — a misspelt required key (`emit_on_change`) is named as the
    // typo it is, not reported as the key it replaced, missing.
    Set<String> declared = refuseUnknownParams(tier, name, params);
    // `column:` names a column wherever a hook declares it (#1465); empty or blank text names none (#1485). A hook
    // whose keys are the columns it fills declares neither: there `column` is a column's value, not this knob.
    if (declared.contains("column") && params.has("column")) {
      ScalarParams.columnName(name + " hook", "column", params.get("column"));
    }
    // `columns:` likewise lists column names wherever a hook declares it; the text cell hooks sharing clean's reading
    // (fix-mojibake, upper, titlecase…) checked nothing, and a scalar or a map acted on nothing, in silence (#1485).
    if (declared.contains("columns") && params.has("columns")) {
      ScalarParams.columnNames(name + " hook", "columns", params.get("columns"));
    }
    check.accept(params);
  }

  /**
   * #1487: refuses a hook this tier does not have, a parameter block that is not a map (#1492), or a key of its
   * parameters the hook does not read; answers the keys the hook declares.
   */
  /** How the hook {@code name} changes a row's columns (#1500); null for a hook the catalog does not know. */
  public ColumnEffect columns(HookDescriptor.Tier tier, String name, JsonNode params) {
    return switch (tier) {
      case DATASET -> { DatasetHook h = dataset(name); yield h == null ? null : h.columns(params); }
      case ROW -> { RowHook h = row(name); yield h == null ? null : h.columns(params); }
      case CELL -> { CellHook h = cell(name); yield h == null ? null : h.columns(params); }
    };
  }

  public Set<String> refuseUnknownParams(HookDescriptor.Tier tier, String name, JsonNode params) {
    Set<String> declared = switch (tier) {
      case DATASET -> { DatasetHook h = dataset(name); yield h == null ? null : h.params(); }
      case ROW -> { RowHook h = row(name); yield h == null ? null : h.params(); }
      case CELL -> { CellHook h = cell(name); yield h == null ? null : h.params(); }
    };
    if (declared == null) throw unknownHook(tier, name);
    // #1492: a scalar or a list has no keys to refuse — `- upper: x` read as no parameters and ran on every column.
    // Bare and `{}` mean no parameters. The shape is named, never the value.
    if (!params.isObject() && !params.isNull() && !params.isMissingNode()) {
      throw new MappingValidationException(name + " hook: its parameters must be a map of name to value (got "
          + MappingValidationException.typeName(params) + ")");
    }
    refuseUnknownKeys(name + " hook", "", params, declared);
    return declared;
  }

  private static MappingValidationException unknownHook(HookDescriptor.Tier tier, String name) {
    return new MappingValidationException("unknown " + tier.name().toLowerCase(Locale.ROOT) + " hook: " + name);
  }

  /**
   * Refuses the first key of {@code block} outside {@code accepted}, named {@code prefix + key}: a misspelt knob is
   * otherwise dropped and the hook runs on its default. A scalar or a bare block has no keys to refuse.
   */
  public static void refuseUnknownKeys(String hook, String prefix, JsonNode block, Set<String> accepted) {
    if (accepted.equals(KEYS_ARE_COLUMNS)) return;   // {"*"}, however a hook builds it: every key is a column
    for (Iterator<String> keys = block.fieldNames(); keys.hasNext(); ) {
      String key = keys.next();
      if (!accepted.contains(key)) throw new UnknownHookParameterException(hook, prefix + key, accepted);
    }
  }

  /** The names of this tier's hooks, sorted. */
  public List<String> names(HookDescriptor.Tier tier) {
    Map<String, ?> byName = switch (tier) {
      case DATASET -> datasetByName;
      case ROW -> rowByName;
      case CELL -> cellByName;
    };
    return byName.keySet().stream().sorted().toList();
  }

  public List<HookDescriptor> allDatasetHooks() {
    return datasetByName.keySet().stream().sorted()
      .map(n -> new HookDescriptor(n, HookDescriptor.Tier.DATASET, "", ""))
      .toList();
  }

  public List<HookDescriptor> allRowHooks() {
    return rowByName.keySet().stream().sorted()
      .map(n -> new HookDescriptor(n, HookDescriptor.Tier.ROW, "", ""))
      .toList();
  }

  public List<HookDescriptor> allCellHooks() {
    return cellByName.keySet().stream().sorted()
      .map(n -> new HookDescriptor(n, HookDescriptor.Tier.CELL, "", ""))
      .toList();
  }
}
