package io.lumnik.hub.entity;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.hub.entity.resolution.NormalizerAllowlist;
import io.lumnik.hub.manifest.HubYaml;
import io.lumnik.hub.manifest.ManifestKeys;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

@ApplicationScoped
public class EntityManifestParser {

  /** {@code SearchIndexManifestParser.INTERNAL_COLUMNS}'s own single source of truth for these
   *  exact system columns (code review, #671): the two blocklists had already drifted apart once
   *  as two independently hand-copied literals, so the search-index side now derives from this
   *  one instead of re-declaring its own copy. */
  public static final Set<String> RESERVED =
      Set.of("_conflicts", "_src", "_ord", "_entity_id", "_resolution", "_source_id", "_handle", "_canonical_key");

  // The honored surface, as an inventory rather than as folklore. apiVersion is DECLARED though
  // parse() ignores it: the shipped manifests carry the apps.lumnik.io/v1 envelope, and refusing it
  // would cry wolf on our own demo — the failure mode the C3 verdict named.
  private static final Set<String> ROOT_KEYS = Set.of(
      "apiVersion", "kind", "name", "label", "match", "sources", "scopes", "description", "columns",
      "resolution", "joins");
  private static final Set<String> SOURCE_KEYS = Set.of("table", "map");
  private static final Set<String> JOIN_KEYS = Set.of("table", "from", "to", "map");
  // A joined column named like a lookup CTE alias would make the CREATE VIEW ambiguous: a 500, not a 400.
  private static final Set<String> LOOKUP_INTERNAL = Set.of(
      EntityViewCompiler.LOOKUP_KEY, EntityViewCompiler.LOOKUP_COUNT, EntityViewCompiler.LOOKUP_AMBIGUITY);
  private static final Set<String> RESOLUTION_KEYS = Set.of("identity", "cascade");
  private static final Set<String> CASCADE_RULE_KEYS = Set.of("id", "exact", "propose");
  private static final Set<String> EXACT_KEYS = Set.of("fields", "normalize");
  private static final Set<String> PROPOSE_KEYS = Set.of("provider", "acceptAbove", "reviewAbove");

  private static final Map<String, Set<String>> ALLOWED_BY_PATH = new LinkedHashMap<>();
  static {
    ALLOWED_BY_PATH.put("", ROOT_KEYS);
    ALLOWED_BY_PATH.put("sources[]", SOURCE_KEYS);
    ALLOWED_BY_PATH.put("joins[]", JOIN_KEYS);
    ALLOWED_BY_PATH.put("resolution", RESOLUTION_KEYS);
    ALLOWED_BY_PATH.put("resolution.cascade[]", CASCADE_RULE_KEYS);
    // `map`, `columns` and `resolution.identity` are keyed by the integrator's OWN names (source
    // columns / canonical columns / source tables), so their keys cannot be inventoried — only the
    // shape one level down could be, and ManifestKeys walks fixed paths. The cost of the inventory's
    // limit is a typo INSIDE one column's grounding (`labl:` for `label:`); the two silences that
    // cost real behaviour, `scope:` and `colums:`, are both root keys.
    //
    // `exact`/`propose` are a DIFFERENT limit: ManifestKeys expresses one array hop per path entry
    // (`resolution.cascade[]`), not an array hop followed by a nested object, so their own keys
    // can't join this table — unknownCascadeShapeKeys() below walks them by hand. Left out of this
    // walk, a typo there (`normalise` for `normalize`) is NOT caught by parse()'s semantic checks
    // either: the manifest's own mapper tolerates unknown properties (C3), so it silently drops to
    // Exact's default empty list and changes the rule's matching semantics with no refusal at all
    // (round 1 on PR #631).
  }

  private static final ObjectMapper KEYS = HubYaml.mapper();

  /**
   * Every key the manifest declares that this parser does not honor, as dotted paths in document
   * order — empty when the manifest is clean, and empty when it does not parse at all (that is
   * {@link #parse}'s error to raise, not this check's).
   *
   * <p>Reporting only, as in {@code ViewManifestParser}: an entity already stored must stay
   * readable. The apply door is where a manifest is refused.
   */
  public List<String> unknownKeys(String yamlText) {
    JsonNode root;
    try {
      root = KEYS.readTree(yamlText);
    } catch (Exception e) {
      return List.of();
    }
    List<String> unknown = new ArrayList<>(ManifestKeys.unknownKeys(root, ALLOWED_BY_PATH));
    unknown.addAll(unknownCascadeShapeKeys(root));
    return List.copyOf(unknown);
  }

  /**
   * {@code resolution.cascade[].exact} and {@code .propose} by hand: {@link ManifestKeys} can
   * express one array hop per path entry, not an array hop followed by a nested object, so these
   * two shapes fall outside {@link #ALLOWED_BY_PATH} — see the comment on that table.
   */
  private static List<String> unknownCascadeShapeKeys(JsonNode root) {
    List<String> unknown = new ArrayList<>();
    JsonNode cascade = root.path("resolution").path("cascade");
    if (!cascade.isArray()) return unknown;
    int i = 0;
    for (JsonNode rule : cascade) {
      String prefix = "resolution.cascade[" + i++ + "].";
      collect(rule.path("exact"), EXACT_KEYS, prefix + "exact.", unknown);
      collect(rule.path("propose"), PROPOSE_KEYS, prefix + "propose.", unknown);
    }
    return unknown;
  }

  private static void collect(JsonNode node, Set<String> allowed, String prefix, List<String> out) {
    if (!node.isObject()) return;
    node.fieldNames().forEachRemaining(f -> { if (!allowed.contains(f)) out.add(prefix + f); });
  }

  /**
   * The honored surface, <b>derived</b> from the inventory above so the refusal can never
   * contradict what is actually accepted.
   */
  public static String honoredSurface() {
    return "root {" + sorted(ROOT_KEYS) + "}, sources[] {" + sorted(SOURCE_KEYS) + "}, "
        + "joins[] {" + sorted(JOIN_KEYS) + "}, "
        + "resolution {" + sorted(RESOLUTION_KEYS) + "}, resolution.cascade[] {"
        + sorted(CASCADE_RULE_KEYS) + "}, resolution.cascade[].exact {" + sorted(EXACT_KEYS)
        + "}, resolution.cascade[].propose {" + sorted(PROPOSE_KEYS) + "}";
  }

  private static String sorted(Set<String> keys) {
    return String.join(", ", new TreeSet<>(keys));
  }

  private final ObjectMapper yaml = HubYaml.mapper()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  /**
   * The refusal catalogue. Each rule below is independent — this method's job is to name them in
   * order, not to hold them. (S3776: the complexity was a LIST of checks, never a knot.)
   */
  public EntityManifest parse(String yamlText) {
    EntityManifest m = deserialize(yamlText);
    requireKindAndName(m);
    requireNotBothMatchAndResolution(m);
    boolean hasResolution = m.resolution() != null;
    if (!hasResolution) requireMatchColumns(m);
    requireSources(m);
    requireJoins(m);
    if (!hasResolution) requireSourcesMapTheMatch(m);
    requireScopes(m);
    Set<String> canonical = new HashSet<>(m.canonicalColumns());
    requireGroundingNamesRealColumns(m, canonical);
    requirePreferredSourcesMapTheirColumn(m);
    requireUncomparedColumnsAreFused(m);
    if (hasResolution) requireResolution(m, canonical);
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

  private static void requireNotBothMatchAndResolution(EntityManifest m) {
    if (m.match() != null && m.resolution() != null)
      throw new IllegalArgumentException(
          "match and resolution are mutually exclusive — declare one or the other");
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
    }
  }


  /**
   * #846: only what the manifest itself can say. {@code to} is a PHYSICAL column and need not be in
   * {@code map}; the apply preflight checks it against the real table.
   */
  private static void requireJoins(EntityManifest m) {
    if (m.joins().isEmpty()) return;
    Set<String> fused = new HashSet<>(m.fusedColumns());
    Set<String> fusedLower = new HashSet<>();
    for (String c : fused) fusedLower.add(c.toLowerCase(Locale.ROOT));
    Set<String> matchLower = new HashSet<>();
    if (m.match() != null) for (String c : m.match()) matchLower.add(c.toLowerCase(Locale.ROOT));
    Set<String> sourceTables = new HashSet<>();
    for (EntityManifest.Source s : m.sources()) sourceTables.add(s.table().toLowerCase(Locale.ROOT));
    Set<String> lookupTables = new HashSet<>();
    Set<String> joinedLower = new HashSet<>();

    for (int i = 0; i < m.joins().size(); i++) {
      EntityManifest.Join j = m.joins().get(i);
      String at = "joins[" + i + "]";
      if (j == null) throw new IllegalArgumentException(at + " is null");
      if (j.table() == null || j.table().isBlank())
        throw new IllegalArgumentException(at + ".table is required");
      String t = j.table().toLowerCase(Locale.ROOT);
      if (sourceTables.contains(t))
        throw new IllegalArgumentException(at + ".table '" + j.table()
            + "' is also a source — a lookup must be a different table");
      if (!lookupTables.add(t))
        throw new IllegalArgumentException(at + ".table '" + j.table() + "' is declared more than once");
      if (j.from() == null || !fused.contains(j.from()))
        throw new IllegalArgumentException(at + ".from '" + j.from()
            + "' is not a canonical column produced by sources[] (known: " + fused + ")");
      if (j.to() == null || j.to().isBlank())
        throw new IllegalArgumentException(at + ".to is required");
      if (j.map() == null || j.map().isEmpty())
        throw new IllegalArgumentException(at + ".map must have at least one column");
      Set<String> seen = new HashSet<>();
      for (Map.Entry<String, String> e : j.map().entrySet()) {
        if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null || e.getValue().isBlank())
          throw new IllegalArgumentException(at + ".map has a blank column");
        String canon = e.getValue().toLowerCase(Locale.ROOT);
        if (RESERVED.contains(canon) || LOOKUP_INTERNAL.contains(canon))
          throw new IllegalArgumentException(at + ": canonical column '" + e.getValue() + "' is reserved");
        if (matchLower.contains(canon))
          throw new IllegalArgumentException(at + ": '" + e.getValue()
              + "' is a match column — a join cannot define identity");
        if (!seen.add(canon))
          throw new IllegalArgumentException(at + ".map maps duplicate canonical column (case-insensitive): " + e.getValue());
        if (fusedLower.contains(canon) || !joinedLower.add(canon))
          throw new IllegalArgumentException(at + ": canonical column '" + e.getValue()
              + "' collides (case-insensitively) with another source or join column");
      }
    }
  }

  private static void requireSourcesMapTheMatch(EntityManifest m) {
    for (EntityManifest.Source s : m.sources())
      requireEverySourceMapsTheMatch(s, m.match());
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
      if (!sourceMapsColumn(s, mc))
        throw new IllegalArgumentException("source '" + s.table() + "' does not map the match column: " + mc);
  }

  /** Whether {@code s}'s own {@code map:} produces the canonical column {@code col} — the one
   * predicate {@code match:} and {@code resolution.identity:} both need, each with its own error. */
  private static boolean sourceMapsColumn(EntityManifest.Source s, String col) {
    return s.map().containsValue(col);
  }

  private static void requireScopes(EntityManifest m) {
    for (String sc : m.scopes()) {
      ScopeGrants.nameProblem(sc).ifPresent(msg -> { throw new IllegalArgumentException(msg); });
    }
  }

  /**
   * Grounding: a `columns:` entry must name a real canonical column (catch typos), and any declared
   * value-domain entry must be non-blank (an empty allowed value would never match and silently 0-row).
   */
  private static void requireGroundingNamesRealColumns(EntityManifest m, Set<String> canonical) {
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

  /**
   * #1379: {@code prefer} names one of {@code sources[]} that maps the column — a column of no source (a join's)
   * is refused the same way — and never a match column, which every source holds alike.
   */
  private static void requirePreferredSourcesMapTheirColumn(EntityManifest m) {
    m.columns().forEach((col, meta) -> {
      if (meta == null || meta.prefer() == null) return;
      if (m.match() != null && m.match().contains(col))
        throw new IllegalArgumentException("column '" + col
            + "' is a match column: every source holds the same value, there is nothing to prefer");
      EntityManifest.Source s = m.sources().stream().filter(x -> meta.prefer().equals(x.table())).findFirst()
          .orElseThrow(() -> new IllegalArgumentException("column '" + col + "' prefers '" + meta.prefer()
              + "', which is not one of sources[] (" + m.sources().stream().map(EntityManifest.Source::table)
                  .collect(Collectors.joining(", ")) + ")"));
      if (!sourceMapsColumn(s, col))
        throw new IllegalArgumentException("column '" + col + "' prefers '" + meta.prefer() + "', which does not map it");
    });
  }

  /**
   * #1449: {@code conflicts: false} on a column the sources fuse; a match column or one no source maps (a join's) is
   * never compared.
   */
  private static void requireUncomparedColumnsAreFused(EntityManifest m) {
    m.columns().forEach((col, meta) -> {
      if (meta == null || meta.conflicts() == null || meta.conflicts()) return;
      if (m.match() != null && m.match().contains(col))
        throw new IllegalArgumentException("column '" + col
            + "' is a match column: every source holds the same value, there is nothing to compare");
      if (m.sources().stream().noneMatch(src -> sourceMapsColumn(src, col)))
        throw new IllegalArgumentException("column '" + col
            + "' is mapped by no source: only fused columns are compared, conflicts: false says nothing");
    });
  }

  private static void requireResolution(EntityManifest m, Set<String> canonical) {
    EntityManifest.Resolution r = m.resolution();
    requireIdentity(m, r);
    requireCascade(r, canonical);
  }

  /**
   * {@code identity:} must cover exactly the declared sources — no more, no fewer — and each
   * column must be one that source's own {@code map:} actually produces (same canonical-column
   * discipline {@code match:} already applies via {@link #requireEverySourceMapsTheMatch}).
   */
  private static void requireIdentity(EntityManifest m, EntityManifest.Resolution r) {
    Map<String, String> identity = r.identity();
    if (identity == null || identity.isEmpty())
      throw new IllegalArgumentException("resolution.identity must declare at least one source");

    Set<String> declaredSources = new HashSet<>();
    for (EntityManifest.Source s : m.sources())
      if (s != null && s.table() != null) declaredSources.add(s.table());

    for (String table : declaredSources)
      if (!identity.containsKey(table))
        throw new IllegalArgumentException(
            "resolution.identity is missing an entry for declared source: " + table);
    for (String table : identity.keySet())
      if (!declaredSources.contains(table))
        throw new IllegalArgumentException(
            "resolution.identity names an undeclared source: " + table);

    for (EntityManifest.Source s : m.sources()) {
      String col = identity.get(s.table());
      if (col == null || col.isBlank())
        throw new IllegalArgumentException(
            "resolution.identity column for source '" + s.table() + "' is blank");
      if (!sourceMapsColumn(s, col))
        throw new IllegalArgumentException("resolution.identity column '" + col
            + "' for source '" + s.table() + "' is not mapped by that source");
    }
  }

  /**
   * The cascade: {@code id:} mandatory and unique, each rule exactly one of {@code exact}/{@code
   * propose} (never both, never neither — a rule that decides nothing and proposes nothing is not
   * a rule).
   */
  private static void requireCascade(EntityManifest.Resolution r, Set<String> canonical) {
    List<EntityManifest.CascadeRule> cascade = r.cascade();
    if (cascade == null || cascade.isEmpty())
      throw new IllegalArgumentException("resolution.cascade must declare at least one rule");

    Set<String> seenIds = new HashSet<>();
    for (EntityManifest.CascadeRule rule : cascade) {
      if (rule == null)
        throw new IllegalArgumentException("resolution.cascade contains a null entry");
      if (rule.id() == null || rule.id().isBlank())
        throw new IllegalArgumentException("resolution.cascade rule is missing an id");
      if (!seenIds.add(rule.id().toLowerCase(Locale.ROOT)))
        throw new IllegalArgumentException(
            "resolution.cascade declares a duplicate id (case-insensitive): " + rule.id());

      boolean hasExact = rule.exact() != null;
      boolean hasPropose = rule.propose() != null;
      if (hasExact == hasPropose)
        throw new IllegalArgumentException("resolution.cascade rule '" + rule.id()
            + "' must declare exactly one of exact or propose");

      if (hasExact) requireExact(rule.id(), rule.exact(), canonical);
      else requirePropose(rule.id(), rule.propose());
    }
  }

  private static void requireExact(String ruleId, EntityManifest.Exact exact, Set<String> canonical) {
    if (exact.fields() == null || exact.fields().isEmpty())
      throw new IllegalArgumentException(
          "resolution.cascade rule '" + ruleId + "' exact.fields must declare at least one field");
    for (String f : exact.fields()) {
      if (f == null || f.isBlank())
        throw new IllegalArgumentException(
            "resolution.cascade rule '" + ruleId + "' exact.fields contains a blank field");
      if (!canonical.contains(f))
        throw new IllegalArgumentException("resolution.cascade rule '" + ruleId
            + "' exact.fields references unknown canonical column '" + f + "'");
    }
    for (String hookId : exact.normalize()) {
      if (!NormalizerAllowlist.isAdmitted(hookId)) {
        String reason = NormalizerAllowlist.EXCLUDED.contains(hookId)
            ? " (explicitly excluded — it can collapse distinct values that are not the intended equivalence)"
            : " (not in the allowlist)";
        throw new IllegalArgumentException("resolution.cascade rule '" + ruleId
            + "' normalize id '" + hookId + "' is not admitted" + reason);
      }
    }
  }

  private static void requirePropose(String ruleId, EntityManifest.Propose propose) {
    if (propose.provider() == null || propose.provider().isBlank())
      throw new IllegalArgumentException(
          "resolution.cascade rule '" + ruleId + "' propose.provider is required");
    if (propose.acceptAbove() == null || propose.reviewAbove() == null)
      throw new IllegalArgumentException(
          "resolution.cascade rule '" + ruleId + "' propose requires acceptAbove and reviewAbove");
    double accept = propose.acceptAbove();
    double review = propose.reviewAbove();
    if (accept < 0 || accept > 1)
      throw new IllegalArgumentException("resolution.cascade rule '" + ruleId
          + "' propose.acceptAbove must be within [0,1]: " + accept);
    if (review < 0 || review > 1)
      throw new IllegalArgumentException("resolution.cascade rule '" + ruleId
          + "' propose.reviewAbove must be within [0,1]: " + review);
    if (accept <= review)
      throw new IllegalArgumentException("resolution.cascade rule '" + ruleId
          + "' propose.acceptAbove must be greater than reviewAbove: " + accept + " vs " + review);
  }
}
