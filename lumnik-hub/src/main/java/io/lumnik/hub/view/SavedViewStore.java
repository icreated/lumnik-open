package io.lumnik.hub.view;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Saved kind:View manifests — the server-side home the PWA discovers views from.
 * Direct SQL over the admin datasource (the EntityRegistry injection shape; no
 * cache: lists are small, reads cold-path). Rows are deployment-global
 * (entity_manifest posture); access control lives in the resources (ScopeGrants).
 */
@ApplicationScoped
public class SavedViewStore {

  public record Summary(String name, String scope, String title) {}
  public record Row(String name, String scope, String title, String yaml) {}

  @Inject
  @DataSource("admin")
  Instance<AgroalDataSource> adminDs;

  public void upsert(String name, String scope, String title, String yaml) {
    // the store owns case normalization on BOTH sides (find lowercases too) — every
    // caller reaches the same row, the EntityRegistry rule
    String key = name.toLowerCase(Locale.ROOT);
    Jdbc.update(adminDs.get(), "SavedViewStore.upsert",
        "INSERT INTO connector.saved_view (name, scope, title, yaml_text) VALUES (?,?,?,?) "
            + "ON CONFLICT (name) DO UPDATE SET scope = EXCLUDED.scope, "
            + "title = EXCLUDED.title, yaml_text = EXCLUDED.yaml_text, updated_at = now()",
        ps -> { ps.setString(1, key); ps.setString(2, scope); ps.setString(3, title); ps.setString(4, yaml); });
  }

  /** scopes == null → all (unscoped user); empty set → none (bound to nothing). */
  public List<Summary> list(Set<String> scopes) {
    if (scopes != null && scopes.isEmpty()) return List.of();
    // Two whole statements rather than one with a conditionally-appended WHERE: each placeholder
    // now sits in the same expression as the value bound to it. That is what a reader checks, and
    // it is what a static analyser can follow — the assembled form read as a bind against a
    // parameterless statement (S2695 BLOCKER, a false positive Sonar had no way to disprove).
    try (Connection c = adminDs.get().getConnection()) {
      if (scopes == null) {
        try (PreparedStatement ps = c.prepareStatement(
                 "SELECT name, scope, title FROM connector.saved_view ORDER BY name")) {
          return summaries(ps);
        }
      }
      try (PreparedStatement ps = c.prepareStatement(
               "SELECT name, scope, title FROM connector.saved_view WHERE scope = ANY (?) ORDER BY name")) {
        ps.setArray(1, c.createArrayOf("text", scopes.toArray()));
        return summaries(ps);
      }
    } catch (Exception e) {
      throw new RuntimeException("SavedViewStore.list: " + e, e);
    }
  }

  private static List<Summary> summaries(PreparedStatement ps) throws SQLException {
    List<Summary> out = new ArrayList<>();
    try (ResultSet rs = ps.executeQuery()) {
      while (rs.next()) out.add(new Summary(rs.getString(1), rs.getString(2), rs.getString(3)));
    }
    return out;
  }

  public Optional<Row> find(String name) {
    // lowercase on read like EntityRegistry.find — apply lowercases on write, so a
    // hand-typed 'Clients-Desk' must reach the same row as 'clients-desk'
    return Jdbc.queryOne(adminDs.get(), "SavedViewStore.find",
        "SELECT name, scope, title, yaml_text FROM connector.saved_view WHERE name = ?",
        ps -> ps.setString(1, name.toLowerCase(Locale.ROOT)),
        rs -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
  }
}
