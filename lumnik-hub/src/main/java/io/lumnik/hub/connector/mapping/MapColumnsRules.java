package io.lumnik.hub.connector.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.connector.mapping.hooks.HookDescriptor.Tier;
import io.lumnik.hub.connector.mapping.spec.HookInvocation;
import io.lumnik.hub.connector.mapping.writer.DynamicTableWriter;
import io.lumnik.hub.sql.SqlIdentifiers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The rules a {@code map-columns} block must satisfy, in one place.
 *
 * <p>They existed in three: {@code JdbcConnectorType.validateRename} (throws at run time from
 * {@code dumpSpec}), {@code JdbcConnectorValidator.validateRename} (accumulates at apply time),
 * and {@code MapColumnsHook} (throws at run time). Same four rules, three renderings — and they
 * had already drifted: a {@code rename} that is not a map was refused at one door and silently
 * ignored at another. This class holds the rule; each door keeps its own exception, its own
 * wording prefix, and its own timing, which is where their differences legitimately live.
 *
 * <p>A knob written as {@code rename:} with nothing after the colon is ABSENT here, not an
 * error, and the same for {@code drop:}. That is the house convention, not this class making up
 * its mind: {@code ConfigDefRunner} computes {@code present = value != null && !value.isNull()}
 * for every declared field of every connector type, and {@code schedule} and {@code window} in
 * the very validators that call this rule return on {@code isMissingNode() || isNull()}. A review
 * read the accepting branch as the accept-and-ignore defect these rules exist to remove; the two
 * differ in what survives. Accept-and-ignore loses a VALUE nothing can show you afterwards — a
 * rename that did not happen leaves every column where it was, under the source's own name, in
 * the table. Pinned in {@code MapColumnsRenameTest#a_knob_with_nothing_after_the_colon_reads_as_absent},
 * so changing the convention is one visible repo-wide decision.
 *
 * <p>A {@link Violation} names the manifest path it is about and the reason as a sentence tail,
 * so a caller writes {@code "map-columns hook: '" + v.path() + "' " + v.reason()} or
 * {@code "config key '" + v.path() + "' " + v.reason()} without the rule knowing either form.
 */
public final class MapColumnsRules {

  private MapColumnsRules() {}

  /** @param path the manifest path this is about, e.g. {@code rename.CUST_NM} or {@code rename}. */
  public record Violation(String path, String reason) {}

  /** Rename rules for a manifest node. Empty when the block is sound. */
  public static List<Violation> checkRename(JsonNode rename) {
    if (rename == null || rename.isMissingNode() || rename.isNull()) return List.of();
    if (!rename.isObject()) {
      return List.of(new Violation("rename",
          "must be a map of source column to target column (got "
          + MappingValidationException.typeName(rename) + ") — for example "
          + "rename: { CUST_NM: name }"));
    }
    List<Violation> out = new ArrayList<>();
    Set<String> targets = new HashSet<>();
    for (var entries = rename.fields(); entries.hasNext(); ) {
      var e = entries.next();
      JsonNode target = e.getValue();
      if (target.isNull()) {
        out.add(nullTarget(e.getKey()));
        continue;
      }
      String name = target.asText();
      if (name.isEmpty()) {          // an array or object target: asText() gives "" — show the node
        out.add(notAPlainIdentifier(e.getKey(), target.toString()));
        continue;
      }
      checkOneTarget(e.getKey(), name, targets, out);
    }
    return out;
  }

  /**
   * The first rename/drop violation anywhere in a STORED connector or endpoint config, or empty.
   *
   * <p>The two write doors that persist config without going through a connector validator share
   * this: {@code ConnectorAdminResource} (connector config — a csv {@code map-columns} block under
   * {@code mapping.row[]}) and {@code EndpointResource} (a jdbc table's top-level {@code rename}).
   * Both shapes are checked, so either door may call this on whichever config it holds. A bad
   * rename accepted here would otherwise reach the run and be caught only by {@code MapColumnsHook}
   * / {@code JdbcConnectorType.validateRename} — after the first row is already lost. This is the
   * write-time twin of those run-time backstops; the apply-time validators keep their own
   * accumulate-all, indexed-path renderings, which a first-fail door does not need.
   *
   * <p>A mapping tier that is not a list is refused too (#1475), with the message
   * {@link HookInvocation#requireList} gives the validator and the run.
   */
  public static Optional<Violation> firstStoredConfigViolation(JsonNode config) {
    if (config == null) return Optional.empty();
    List<Violation> topRename = checkRename(config.path("rename"));
    if (!topRename.isEmpty()) return Optional.of(topRename.get(0));
    JsonNode mapping = config.path("mapping");
    for (Tier t : Tier.values()) {
      String tier = t.name().toLowerCase(Locale.ROOT);
      try {
        HookInvocation.requireList(mapping, tier);
      } catch (MappingValidationException e) {
        return Optional.of(new Violation("mapping." + tier, e.getMessage()));
      }
    }
    for (JsonNode hook : mapping.path("row")) {   // an array, missing or null: checked just above
      JsonNode mc = hook.path("map-columns");
      if (mc.isMissingNode()) continue;
      List<Violation> v = checkRename(mc.path("rename"));
      if (v.isEmpty()) v = checkDrop(mc.path("drop"));
      if (!v.isEmpty()) return Optional.of(v.get(0));
    }
    return Optional.empty();
  }

  /** {@code drop} must be a list of column names, or it removes nothing and says nothing. */
  public static List<Violation> checkDrop(JsonNode drop) {
    if (drop == null || drop.isMissingNode() || drop.isNull()) return List.of();
    if (!drop.isArray()) {
      return List.of(new Violation("drop",
          "must be a list of column names (got " + MappingValidationException.typeName(drop)
          + ") — for example drop: [internal_flag]"));
    }
    List<Violation> out = new ArrayList<>();
    for (JsonNode col : drop) {
      if (!col.isTextual()) {
        out.add(new Violation("drop", "every entry must be a column name (got "
            + MappingValidationException.typeName(col) + ")"));
      }
    }
    return out;
  }

  private static Violation nullTarget(String source) {
    return new Violation("rename." + source,
        "must name a target column — a colon with nothing after it is null, not a rename target, "
        + "and 'null' would otherwise become the column's literal name");
  }

  private static Violation notAPlainIdentifier(String source, String shown) {
    return new Violation("rename." + source,
        "must be a plain identifier (letters, digits, underscore; not starting with a digit): "
        + shown);
  }

  private static void checkOneTarget(String source, String name,
                                     Set<String> targets, List<Violation> out) {
    if (!SqlIdentifiers.isPlainIdentifier(name)) {
      out.add(notAPlainIdentifier(source, name));
      return;
    }
    if (DynamicTableWriter.SYSTEM_COLUMNS.contains(name)) {
      out.add(new Violation("rename." + source,
          "targets '" + name + "', a hub system column (" + DynamicTableWriter.systemColumnList()
          + ") — renaming a source column AWAY from one of these is what the rename is for, "
          + "renaming into one only recreates the collision"));
      return;
    }
    if (!targets.add(name)) {
      out.add(new Violation("rename",
          "targets '" + name + "' from more than one source column — one of them would be "
          + "silently lost"));
    }
  }
}
