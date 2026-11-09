package io.lumnik.hub.entity;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.quarkus.agroal.DataSource;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@ApplicationScoped
public class EntityRegistry {

  private static final Logger LOG = Logger.getLogger(EntityRegistry.class);

  @Inject
  @DataSource("admin")
  Instance<AgroalDataSource> adminDsInstance;

  @Inject
  Instance<EntityManifestParser> parserInstance;

  private final Map<String, EntityManifest> byName = new ConcurrentHashMap<>();

  /** What the table looked like when this node last read it; {@code null} until it has. */
  private final AtomicReference<String> seen = new AtomicReference<>();

  /**
   * Applies and deletes this node made, counted under {@link #swapLock}: a refresh that read before one and swapped
   * after it must not take it back.
   */
  private long localWrites;

  /**
   * Held for the in-memory mutations only (never a database read or a parse): the refresh's generation check and its
   * swap, every other swap (the miss path's too), {@link #put} and {@link #remove}. A write landing between the check and the swap would otherwise be taken back.
   */
  final Object swapLock = new Object();

  /** Test seam: runs after the refresh's generation check, inside the lock, before the swap. */
  Runnable afterCheck = () -> {};

  /** The fingerprint whose unreadable row was last said, so a row that stays unreadable is said once, not every tick. */
  private volatile String reported;

  @PostConstruct
  void load() {
    if (adminDsInstance.isUnsatisfied()) return;
    loadFromDb();
  }

  /** One stored Entity as the table holds it: the key, and the YAML not yet read. */
  record Row(String name, String yaml) {}

  /** Every stored row, unparsed. A failure to reach the table is the server's and propagates; it is never "no such Entity". */
  List<Row> readRows() {
    return Jdbc.query(adminDsInstance.get(), "EntityRegistry.loadFromDb", "SELECT name, yaml_text FROM connector.entity_manifest",
        ps -> {}, rs -> new Row(rs.getString("name"), rs.getString("yaml_text")));
  }

  /** The row as an Entity; a row whose name is not its YAML's name is a data-integrity violation. */
  private EntityManifest parse(Row row) {
    EntityManifest m = parserInstance.get().parse(row.yaml());
    if (!m.name().toLowerCase(Locale.ROOT).equals(row.name()))
      throw new IllegalStateException("entity_manifest row '" + row.name()
          + "' has yaml name '" + m.name() + "' — names diverged, manual intervention required");
    return m;
  }

  /** Reloads all manifests from the DB into a temp map, then swaps atomically. Package-private for testing. */
  void loadFromDb() {
    String fingerprint = fingerprint();
    publish(readRows());
    seen.set(fingerprint);
  }

  /**
   * How the stored Entities look right now, in one statement: a digest of every row's name, YAML and scopes. Of the
   * content, not of a timestamp: {@code updated_at} is the time a transaction BEGAN, so a re-apply that commits after a
   * later-started one would leave a count and a latest write that did not move. Read BEFORE the rows, so a write that
   * lands between the two is seen again on the next tick, never missed.
   */
  String fingerprint() {
    return Jdbc.queryOne(adminDsInstance.get(), "EntityRegistry.fingerprint",
        "SELECT COALESCE(md5(string_agg(name || ':' || md5(yaml_text) || ':' || scopes::text, '|' ORDER BY name)), '') "
        + "FROM connector.entity_manifest",
        ps -> {}, rs -> rs.getString(1)).orElse("");
  }

  /**
   * Follows the table (#1836). {@code entity_manifest} is global but each node keeps a copy, loaded at start and on a
   * miss: an Entity applied or deleted on another node was never seen here. The gate that stages a source's events and
   * the consumers that read them decide from this copy, so a source added elsewhere was not staged and its change was
   * missed for good. Each tick asks the table for its fingerprint and reads the rows only when it moved.
   *
   * <p>A node is at most one tick behind, not zero: a change landing in that window on this node is still decided on the
   * previous definition. A definition this node cannot read keeps the last one it could, and the next tick tries again
   * (said once per change): a failure that was not the definition's own heals by itself.
   */
  void refreshIfChanged() {
    String fingerprint = fingerprint();
    if (fingerprint.equals(seen.get())) return;
    long writes;
    synchronized (swapLock) {
      writes = localWrites;
    }
    List<Row> rows = readRows();
    Map<String, EntityManifest> temp = new LinkedHashMap<>();
    boolean allRead = true;
    for (Row row : rows) {
      try {
        EntityManifest m = parse(row);
        temp.put(m.name().toLowerCase(Locale.ROOT), m);
      } catch (RuntimeException unreadable) {
        allRead = false;
        if (!fingerprint.equals(reported)) {
          LOG.errorf("EntityRegistry: the stored Entity '%s' cannot be read (%s) — keeping the definition this node has",
              row.name(), describe(unreadable));
        }
        LOG.debug("EntityRegistry refresh failure", unreadable);
        String key = row.name().toLowerCase(Locale.ROOT);   // the readable path's key, whatever the row's spelling
        EntityManifest previous = byName.get(key);
        if (previous != null) temp.put(key, previous);
      }
    }
    // An apply or a delete on THIS node landed while the rows were read: they may be older than what it just did, and
    // swapping them in would take it back. The next tick reads again.
    synchronized (swapLock) {
      if (writes != localWrites) return;
      afterCheck.run();
      swap(temp);
    }
    if (allRead) seen.set(fingerprint);
    else reported = fingerprint;
  }

  @Scheduled(every = "${lumnik.entity.registry.refresh-every:3s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void tick() {
    if (adminDsInstance.isUnsatisfied()) return;
    try {
      refreshIfChanged();
    } catch (RuntimeException e) {
      LOG.errorf("EntityRegistry: the stored Entities cannot be re-read (%s) — keeping this node's copy", describe(e));
      LOG.debug("EntityRegistry refresh failure", e);
    }
  }

  private void publish(List<Row> rows) {
    Map<String, EntityManifest> temp = new LinkedHashMap<>();
    for (Row row : rows) {
      EntityManifest m = parse(row);
      temp.put(m.name().toLowerCase(Locale.ROOT), m);
    }
    swap(temp);
  }

  /**
   * Refresh WITHOUT ever publishing an empty map. clear()+putAll() exposes a transient
   * "registry is empty" window to concurrent readers — harmless at @PostConstruct boot, but
   * findReloadingIfMissing runs this on the request path, where that window is a false 404 /
   * misclassification. Add-or-update every current row first, then prune the keys the DB no longer
   * has: at no instant is a live entity absent from the map.
   */
  private void swap(Map<String, EntityManifest> current) {
    synchronized (swapLock) {   // also from the miss path: its prune must not interleave with a put or a remove
      byName.putAll(current);
      byName.keySet().retainAll(current.keySet());
    }
  }

  public void put(EntityManifest m) {
    synchronized (swapLock) {
      localWrites++;
      byName.put(m.name().toLowerCase(Locale.ROOT), m);
    }
  }

  public EntityManifest find(String name) {
    return name == null ? null : byName.get(name.toLowerCase(Locale.ROOT));
  }

  /**
   * {@link #find} that re-reads the global manifests from the DB once on a cache miss, then retries.
   * {@code entity_manifest} is global, so a second hub instance's cold/stale map must not be trusted
   * to say an entity does not exist — every decision keyed on existence (delete's 404, the
   * connector-delete 409's entity-vs-foreign wording) goes through here to stay drift-free.
   */
  public EntityManifest findReloadingIfMissing(String name) {
    EntityManifest m = find(name);
    if (m == null) {
      List<Row> rows = readRows();   // a failure to reach the table propagates: it is not a miss
      try {
        publish(rows);
      } catch (RuntimeException e) {
        // The reload parses EVERY stored Entity: one unreadable row must not hide the Entity asked for. Said, then this
        // one row — from the rows already read, so nothing here can turn a database failure into a miss — is read on its own,
        // with the same name check.
        LOG.errorf("EntityRegistry: the manifests cannot all be reloaded (%s) — reading '%s' on its own", describe(e), name);
        LOG.debug("EntityRegistry reload failure", e);
        String key = name.toLowerCase(Locale.ROOT);
        for (Row row : rows) {
          if (!row.name().equals(key)) continue;
          try {
            EntityManifest one = parse(row);
            byName.put(key, one);
          } catch (RuntimeException unreadable) {
            LOG.errorf("EntityRegistry: the stored Entity '%s' cannot be read (%s)", key, describe(unreadable));
            LOG.debug("EntityRegistry single read failure", unreadable);
          }
        }
      }
      m = find(name);
    }
    return m;
  }

  /** The registry's own words for an invariant it broke (they name the row, never the YAML); only the kind for a parser's, which quote the text. */
  private static String describe(RuntimeException e) {
    return e instanceof IllegalStateException ? e.getMessage() : e.getClass().getSimpleName();
  }

  /**
   * The scopes STORED on this name, read from the row rather than from the map, or empty when the
   * name is free — the apply door's re-homing check ({@code ApplyScopeBoundary}).
   *
   * <p>Not {@link #findReloadingIfMissing}: that answers from the cache whenever the key is present,
   * and a cache HIT is exactly the stale case here. Another hub instance may have re-applied the
   * entity into a different scope set, and this map would still hold the old one — a security
   * decision must not be made on a copy that can be behind. A cold-path read on apply.
   *
   * <p>An empty list is a real answer (a scopeless entity), distinct from an absent row.
   */
  public Optional<List<String>> storedScopes(String name) {
    return stored(name).map(Stored::scopes);
  }

  /** One entity as stored right now: its exact YAML (a version other writes can bind to) and scopes. */
  public record Stored(String yaml, List<String> scopes) {}

  /**
   * The row itself — the same cold-path read as {@link #storedScopes}, for a door that must decide on
   * the stored definition rather than on this instance's copy (#669: a SearchIndex apply validates
   * columns and scopes against it, then binds its write to {@code yaml}).
   */
  public Optional<Stored> stored(String name) {
    if (name == null) return Optional.empty();
    return Jdbc.queryOne(adminDsInstance.get(), "EntityRegistry.stored",
        "SELECT yaml_text, scopes FROM connector.entity_manifest WHERE name = ?",
        ps -> ps.setString(1, name.toLowerCase(Locale.ROOT)),
        rs -> new Stored(rs.getString(1), scopes(rs.getArray(2))));
  }

  /**
   * A {@code text[]} of scope tags as a list. Arrays.asList, not List.of: a NULL element in the text[]
   * would make List.of throw, and a malformed row must be REFUSED (allowsAll sees a tag nobody holds),
   * not answered 500.
   */
  public static List<String> scopes(Array arr) throws SQLException {
    return arr == null ? List.of() : Arrays.asList((String[]) arr.getArray());
  }

  /** Drop a manifest from the in-memory map (its row is removed from the DB by the compiler). */
  public void remove(String name) {
    synchronized (swapLock) {
      localWrites++;
      if (name != null) byName.remove(name.toLowerCase(Locale.ROOT));
    }
  }

  /** Every registered manifest, sorted by name — deterministic iteration for the aggregate reads. */
  public List<EntityManifest> all() {
    return byName.values().stream()
        .sorted(Comparator.comparing(EntityManifest::name, String.CASE_INSENSITIVE_ORDER))
        .toList();
  }
}
