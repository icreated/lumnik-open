package io.lumnik.hub.connector.jdbc.dialect;

import io.lumnik.jdbc.spi.JdbcDialect;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The installed dialect providers, and the one door that picks one per connector.
 *
 * <p>Every name a provider claims — its {@code id()} and its {@code aliases()}, compared after
 * {@code trim().toLowerCase(Locale.ROOT)} — belongs to exactly one provider: a name claimed twice
 * fails startup rather than letting the last bean win. Resolution is strict: an explicit
 * {@code dialect} is authoritative (and the URL must be one it accepts); an absent one is inferred
 * only when exactly one provider accepts the URL — never "first wins".
 *
 * <p>{@code @Startup}: an {@code @ApplicationScoped} bean is built lazily, so without it the duplicate-name
 * refusal would fire on the first JDBC request (as a 500) while the hub looked healthy.
 */
@Startup
@ApplicationScoped
public class JdbcDialectRegistry {

  private static final Logger LOG = Logger.getLogger(JdbcDialectRegistry.class);

  @Inject Instance<JdbcDialect> dialectBeans;

  /** Every claimed name (id and aliases, normalized) -> its provider. */
  private final Map<String, JdbcDialect> byName = new HashMap<>();

  @PostConstruct
  void init() {
    register(dialectBeans);
    if (byName.isEmpty()) {
      LOG.warn("No JDBC dialect provider is installed: every jdbc-generic connector will be refused. "
          + "The hub ships lumnik-jdbc-postgresql; a build without it needs another provider.");
    }
  }

  /** A registry over the given providers, for tests that do not boot CDI. */
  public static JdbcDialectRegistry of(Iterable<JdbcDialect> dialects) {
    JdbcDialectRegistry r = new JdbcDialectRegistry();
    r.register(dialects);
    return r;
  }

  private void register(Iterable<JdbcDialect> dialects) {
    for (JdbcDialect d : dialects) {
      List<String> names = new ArrayList<>();
      names.add(d.id());
      names.addAll(d.aliases());
      for (String raw : names) {
        String name = normalize(raw);
        JdbcDialect previous = byName.put(name, d);
        if (previous != null && previous != d) {
          throw new IllegalStateException("JDBC dialect name '" + name + "' is claimed by both "
              + previous.getClass().getName() + " and " + d.getClass().getName());
        }
      }
    }
  }

  private static String normalize(String name) {
    return name.trim().toLowerCase(Locale.ROOT);
  }

  /** The installed providers, once each, sorted by id — deterministic messages and inference. */
  private List<JdbcDialect> providers() {
    return byName.values().stream().distinct()
        .sorted(Comparator.comparing((JdbcDialect d) -> normalize(d.id()))).toList();
  }

  /** The installed dialect ids, sorted. */
  public List<String> installedIds() {
    return providers().stream().map(d -> normalize(d.id())).toList();
  }

  /** The provider an explicit {@code dialect} value names — by id or alias, case-insensitive. */
  public JdbcDialect resolve(String name) {
    JdbcDialect d = name == null ? null : byName.get(normalize(name));
    if (d == null) {
      throw new IllegalArgumentException(
          "JDBC dialect '" + name + "' is not installed. Available: " + String.join(", ", installedIds()));
    }
    return d;
  }

  /**
   * The provider for a connector: the explicit {@code dialect} when given (and the URL must be one it
   * accepts), else the only installed provider that accepts the URL.
   */
  public JdbcDialect resolveFor(String url, String explicitDialect) {
    if (explicitDialect != null && !explicitDialect.isBlank()) {
      JdbcDialect d = resolve(explicitDialect);
      if (!d.acceptsUrl(url)) {
        throw new IllegalArgumentException("JDBC dialect '" + normalize(explicitDialect)
            + "' does not accept url scheme '" + scheme(url) + "'");
      }
      return d;
    }
    List<JdbcDialect> accepting = providers().stream().filter(d -> d.acceptsUrl(url)).toList();
    if (accepting.size() == 1) return accepting.get(0);
    if (accepting.isEmpty()) {
      throw new IllegalArgumentException("no installed JDBC dialect accepts url scheme '" + scheme(url)
          + "' — install its provider, or set 'dialect'. Available: " + String.join(", ", installedIds()));
    }
    throw new IllegalArgumentException("url scheme '" + scheme(url) + "' is claimed by several dialects: "
        + String.join(", ", accepting.stream().map(JdbcDialect::id).toList())
        + " — set 'dialect' explicitly");
  }

  /** {@code jdbc:oracle} out of {@code jdbc:oracle:thin:...} — enough to say what was refused. */
  private static String scheme(String url) {
    if (url == null) return "null";
    int first = url.indexOf(':');
    int second = first < 0 ? -1 : url.indexOf(':', first + 1);
    return second < 0 ? url : url.substring(0, second);
  }
}
