package io.lumnik.hub.entity;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * A v1 canonical entity fused from one or more sources.
 *
 * @param match      canonical column(s) of the single v1 match rule (composite if &gt;1). Mutually
 *                   exclusive with {@code resolution} — a manifest declares one or the other.
 * @param sources    ordered list of sources; the order is the deterministic merge tiebreak (_ord).
 * @param resolution the #613 cascade policy (null when the manifest uses {@code match} instead).
 * @param joins      the #846 relational lookups: they enrich the fused rows and never fuse or add one.
 */
public record EntityManifest(String kind, String name, String label,
    List<String> match, List<Source> sources, List<String> scopes,
    String description, Map<String, ColumnMeta> columns, Resolution resolution,
    List<Join> joins) {

  /** Null-safe collections (a manifest with no scopes is simply not discoverable; no columns = no extra grounding). */
  public EntityManifest {
    scopes = scopes == null ? List.of() : scopes;
    columns = columns == null ? Map.of() : columns;
    joins = joins == null ? List.of() : joins;
  }

  /** The pre-#846 shape: no joins. Keeps every existing call site valid. */
  public EntityManifest(String kind, String name, String label,
      List<String> match, List<Source> sources, List<String> scopes,
      String description, Map<String, ColumnMeta> columns, Resolution resolution) {
    this(kind, name, label, match, sources, scopes, description, columns, resolution, List.of());
  }

  /** Convenience for Java callers/tests that don't set scopes/grounding/resolution. */
  public EntityManifest(String kind, String name, List<String> match, List<Source> sources) {
    this(kind, name, null, match, sources, List.of(), null, Map.of(), null, List.of());
  }

  /** One source: a connector dynamic table + its sourceColumn -&gt; canonicalColumn map (ordered). */
  public record Source(String table,
      @JsonDeserialize(as = LinkedHashMap.class) Map<String, String> map) {}

  /**
   * One relational lookup (#846): enriches the fused rows from {@code table} without fusing
   * anything. {@code from} is a CANONICAL column of the fused entity; {@code to} a PHYSICAL column
   * of the lookup; {@code map} is {@code lookup column: canonical column}, as for a source.
   */
  public record Join(String table, String from, String to,
      @JsonDeserialize(as = LinkedHashMap.class) Map<String, String> map) {}

  /**
   * Per-canonical-column grounding. A {@code label} is the human display name a surface (kind:View,
   * the SchemaCard) shows instead of the raw canonical name; it flows to {@code SchemaCard.Column.label}
   * (null/blank falls back to the canonical name). The rest feeds the SQL-generation prompt so the model
   * stops guessing: a business {@code description}, an {@code example} value, and the allowed {@code values}
   * domain (enforced by the DomainGuard — a filter on a value not in this set is refused). {@code prefer} names
   * the source table that goes first for this column's value (#1379): each system is the reference for its own
   * fields; the other sources keep the declared order, and the {@code _conflicts} ledger keeps the same
   * entries, the preferred source's first. {@code conflicts: false} (#1449) says the column's sources progress rather
   * than disagree — a stage each flow stamps — so its value follows the same order and it is never compared: no
   * {@code _conflicts} entry, no disputed row. Null means compared.
   */
  public record ColumnMeta(String label, String description, String example, List<String> values, String prefer,
                           Boolean conflicts) {
    public ColumnMeta {
      values = values == null ? List.of() : values;
    }
  }

  /**
   * The #613 resolution policy: which column identifies a row of each source, and the ordered
   * cascade that resolves a source row to a canonical entity. See design doc §4.
   *
   * @param identity source table -&gt; its canonical identity column (v1: one column per source).
   * @param cascade  ordered rules; the cascade stops only on an unambiguous accepted decision.
   */
  public record Resolution(Map<String, String> identity, List<CascadeRule> cascade) {

    /**
     * The physical column of {@code s} carrying this resolution's declared identity for its
     * table — the canonical column {@link #identity} names, resolved through {@code s}'s own
     * {@code map()} to the physical column that produces it. Shared by {@code EntityViewCompiler}
     * (the live view's join) and {@code EntityResolutionPass} (the post-run pass), so "which
     * physical column identifies a row of this source" is expressed once, not independently
     * retyped in each consumer.
     */
    public String physicalIdentityColumn(Source s) {
      String identityCanon = identity.get(s.table());
      if (identityCanon == null || identityCanon.isBlank())
        throw new IllegalStateException("source '" + s.table()
            + "' has no resolution.identity column — manifest must be validated before use");
      // Last match wins, not first: mirrors the canonical->physical map this method replaces
      // (built via repeated put(), so a source that maps the same canonical column from two
      // physical columns — not forbidden by EntityManifestParser — let the later mapping
      // overwrite the earlier one). Keeping that exact tiebreak makes this extraction a pure
      // refactor rather than a silent behavior change for a duplicate-mapped manifest.
      String physicalCol = null;
      for (Map.Entry<String, String> e : s.map().entrySet()) {
        if (identityCanon.equals(e.getValue())) physicalCol = e.getKey();
      }
      if (physicalCol == null)
        throw new IllegalStateException("resolution.identity column '" + identityCanon + "' for source '"
            + s.table() + "' is not mapped by that source — manifest must be validated before use");
      return physicalCol;
    }
  }

  /**
   * One cascade step: exactly one of {@code exact} (native match) or {@code propose} (a candidate
   * provider) — never both, never neither.
   *
   * @param id evidence names this rule by it, and the fingerprint versions it (design doc §6).
   */
  public record CascadeRule(String id, Exact exact, Propose propose) {}

  /**
   * A native exact-match rule: normalize each field (allowlisted hook ids), then look up by the
   * resulting fingerprint. The object form is the only shape — no shorthand.
   */
  public record Exact(List<String> fields, List<String> normalize) {
    public Exact {
      normalize = normalize == null ? List.of() : normalize;
    }
  }

  /** A candidate provider step. The verb {@code propose} states it never decides. */
  public record Propose(String provider, Double acceptAbove, Double reviewAbove) {}

  /** What fusion produces: the ordered, de-duplicated union of every source's map values (match key included). */
  public List<String> fusedColumns() {
    LinkedHashSet<String> cols = new LinkedHashSet<>();
    if (sources != null)
      for (Source s : sources)
        if (s != null && s.map() != null) cols.addAll(s.map().values());
    return new ArrayList<>(cols);
  }

  /** What the lookups add, in {@code joins[]} then {@code map} order. */
  public List<String> joinedColumns() {
    LinkedHashSet<String> cols = new LinkedHashSet<>();
    for (Join j : joins)
      if (j != null && j.map() != null) cols.addAll(j.map().values());
    return new ArrayList<>(cols);
  }

  /**
   * Everything the compiled view exposes: fused, then joined. Every consumer (read, filters, schema
   * card, grounding, export, search definition) asks this, so a joined column reaches them without a
   * list of places to remember; only the compiler needs {@link #fusedColumns()}.
   */
  public List<String> canonicalColumns() {
    LinkedHashSet<String> cols = new LinkedHashSet<>(fusedColumns());
    cols.addAll(joinedColumns());
    return new ArrayList<>(cols);
  }
}
