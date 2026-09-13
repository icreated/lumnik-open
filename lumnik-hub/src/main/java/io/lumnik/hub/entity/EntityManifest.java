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
 * @param match   canonical column(s) of the single v1 match rule (composite if &gt;1). Cascade deferred.
 * @param sources ordered list of sources; the order is the deterministic merge tiebreak (_ord).
 */
public record EntityManifest(String kind, String name, String label,
    List<String> match, List<Source> sources, List<String> scopes,
    String description, Map<String, ColumnMeta> columns) {

  /** Null-safe collections (a manifest with no scopes is simply not discoverable; no columns = no extra grounding). */
  public EntityManifest {
    scopes = scopes == null ? List.of() : scopes;
    columns = columns == null ? Map.of() : columns;
  }

  /** Convenience for Java callers/tests that don't set scopes/grounding. */
  public EntityManifest(String kind, String name, List<String> match, List<Source> sources) {
    this(kind, name, null, match, sources, List.of(), null, Map.of());
  }

  /** One source: a connector dynamic table + its sourceColumn -&gt; canonicalColumn map (ordered). */
  public record Source(String table,
      @JsonDeserialize(as = LinkedHashMap.class) Map<String, String> map) {}

  /**
   * Per-canonical-column grounding. A {@code label} is the human display name a surface (kind:View,
   * the SchemaCard) shows instead of the raw canonical name; it flows to {@code SchemaCard.Column.label}
   * (null/blank falls back to the canonical name). The rest feeds the SQL-generation prompt so the model
   * stops guessing: a business {@code description}, an {@code example} value, and the allowed {@code values}
   * domain (enforced by the DomainGuard — a filter on a value not in this set is refused).
   */
  public record ColumnMeta(String label, String description, String example, List<String> values) {
    public ColumnMeta {
      values = values == null ? List.of() : values;
    }
  }

  /** Canonical columns = ordered, de-duplicated union of every source's map values (match key included). */
  public List<String> canonicalColumns() {
    LinkedHashSet<String> cols = new LinkedHashSet<>();
    if (sources != null)
      for (Source s : sources)
        if (s != null && s.map() != null) cols.addAll(s.map().values());
    return new ArrayList<>(cols);
  }
}
