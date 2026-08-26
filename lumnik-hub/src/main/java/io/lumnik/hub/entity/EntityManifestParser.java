package io.lumnik.hub.entity;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class EntityManifestParser {

  private static final Set<String> RESERVED = Set.of("_conflicts", "_src", "_ord");

  private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory())
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  /**
   * The refusal catalogue. Each rule below is independent — this method's job is to name them in
   * order, not to hold them. (S3776: the complexity was a LIST of checks, never a knot.)
   */
  public EntityManifest parse(String yamlText) {
    EntityManifest m = deserialize(yamlText);
    requireKindAndName(m);
    requireMatchColumns(m);
    requireSources(m);
    requireScopes(m);
    requireGroundingNamesRealColumns(m);
    return m;
  }

  private EntityManifest deserialize(String yamlText) {
    try {
      return yaml.readValue(yamlText, EntityManifest.class);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid Entity manifest: " + e.getMessage(), e);
    }
  }

  private static void requireKindAndName(EntityManifest m) {
    if (m.kind() == null || !"Entity".equals(m.kind()))
      throw new IllegalArgumentException("kind must be 'Entity'");
    if (m.name() == null || m.name().isBlank())
      throw new IllegalArgumentException("name is required");
    if (!m.name().matches("[A-Za-z0-9_]+"))
      throw new IllegalArgumentException(
          "name must be a simple identifier (letters, digits, _) — it becomes the SQL view name "
          + "v_<name> advertised to the chat: " + m.name());
  }

  private static void requireMatchColumns(EntityManifest m) {
    if (m.match() == null || m.match().isEmpty())
      throw new IllegalArgumentException("match must declare at least one column");
    Set<String> matchSeen = new HashSet<>();
    for (String mc : m.match()) {
      if (mc == null || mc.isBlank())
        throw new IllegalArgumentException("match contains a blank column");
      if (!matchSeen.add(mc.toLowerCase(Locale.ROOT)))
        throw new IllegalArgumentException("match declares a duplicate column (case-insensitive): " + mc);
    }
  }

  private static void requireSources(EntityManifest m) {
    if (m.sources() == null || m.sources().isEmpty())
      throw new IllegalArgumentException("sources must have at least one source");

    // Tracks each canonical name (lower-cased) -> the exact-case spelling first seen, ACROSS sources:
    // canonical names are case-sensitive identifiers in the generated SQL, so `email` in one source
    // and `Email` in another would silently become two columns (almost always an accidental typo).
    Map<String, String> canonAcrossSources = new HashMap<>();
    for (EntityManifest.Source s : m.sources()) {
      requireSourceShape(s);
      requireMappedColumns(s, canonAcrossSources);
      requireEverySourceMapsTheMatch(s, m.match());
    }
  }

  private static void requireSourceShape(EntityManifest.Source s) {
    if (s == null)
      throw new IllegalArgumentException("sources contains a null entry");
    if (s.table() == null || s.table().isBlank())
      throw new IllegalArgumentException("source table is required");
    if (s.map() == null || s.map().isEmpty())
      throw new IllegalArgumentException("source '" + s.table() + "' map must have at least one column");
  }

  private static void requireMappedColumns(EntityManifest.Source s, Map<String, String> canonAcrossSources) {
    Set<String> seen = new HashSet<>();
    for (Map.Entry<String, String> e : s.map().entrySet()) {
      if (e.getKey() == null || e.getKey().isBlank())
        throw new IllegalArgumentException("source '" + s.table() + "' has a blank source column key");
      if (e.getValue() == null || e.getValue().isBlank())
        throw new IllegalArgumentException("source '" + s.table() + "' has a blank canonical value for key: " + e.getKey());
      String canon = e.getValue().toLowerCase(Locale.ROOT);
      if (RESERVED.contains(canon))
        throw new IllegalArgumentException("source '" + s.table() + "': canonical column '" + e.getValue() + "' is reserved");
      if (!seen.add(canon))
        throw new IllegalArgumentException("source '" + s.table() + "' maps duplicate canonical column (case-insensitive): " + e.getValue());
      String prior = canonAcrossSources.putIfAbsent(canon, e.getValue());
      if (prior != null && !prior.equals(e.getValue()))
        throw new IllegalArgumentException("canonical column '" + e.getValue() + "' conflicts with '" + prior
            + "' across sources (case-insensitive collision); use one consistent case");
    }
  }

  /**
   * Exact-case (not lowercased): quoted identifiers are case-sensitive in the generated SQL, so a
   * source must produce the match column with the SAME case the GROUP BY will reference, else the
   * view compiles a GROUP BY against a column no branch projects.
   */
  private static void requireEverySourceMapsTheMatch(EntityManifest.Source s, List<String> match) {
    for (String mc : match)
      if (!s.map().containsValue(mc))
        throw new IllegalArgumentException("source '" + s.table() + "' does not map the match column: " + mc);
  }

  private static void requireScopes(EntityManifest m) {
    for (String sc : m.scopes()) {
      if (sc == null || sc.isBlank())
        throw new IllegalArgumentException("scopes contains a blank entry");
      if (!sc.matches("[A-Za-z0-9_-]+"))
        throw new IllegalArgumentException(
            "scope must be a simple identifier (letters, digits, _ or -): " + sc);
    }
  }

  /**
   * Grounding: a `columns:` entry must name a real canonical column (catch typos), and any declared
   * value-domain entry must be non-blank (an empty allowed value would never match and silently 0-row).
   */
  private static void requireGroundingNamesRealColumns(EntityManifest m) {
    Set<String> canonical = new HashSet<>(m.canonicalColumns());
    for (Map.Entry<String, EntityManifest.ColumnMeta> e : m.columns().entrySet()) {
      if (!canonical.contains(e.getKey()))
        throw new IllegalArgumentException(
            "columns references unknown canonical column '" + e.getKey() + "' (known: " + canonical + ")");
      EntityManifest.ColumnMeta meta = e.getValue();
      if (meta == null) continue; // a declared key with no grounding (YAML `col:`) — harmless
      for (String v : meta.values()) {
        if (v == null || v.isBlank())
          throw new IllegalArgumentException("column '" + e.getKey() + "' has a blank allowed value");
        if (!v.equals(v.strip()))
          throw new IllegalArgumentException("column '" + e.getKey()
              + "' has an allowed value with leading/trailing whitespace (would never exact-match): '" + v + "'");
      }
    }
  }
}
