package io.lumnik.hub.view;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.api.ScopeGuardedUpsert;
import io.lumnik.hub.tenant.ManifestOwner;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Saved kind:View manifests — the server-side home the PWA discovers views from.
 * Direct SQL over the admin datasource (the EntityRegistry injection shape; no
 * cache: lists are small, reads cold-path). A name is unique on the hub; a row is read by its owner only, or by
 * every tenant when it has none ({@link ManifestOwner}, #2050). Scope control lives in the resources (ScopeGrants).
 */
@ApplicationScoped
public class SavedViewStore {

  public record Summary(String name, String scope, String title) {}
  public record Row(String name, String scope, String title, String yaml) {}

  @Inject
  @DataSource("admin")
  Instance<AgroalDataSource> adminDs;

  /**
   * Upsert this view, refusing to overwrite a name the caller's grants do not reach.
   *
   * <p>{@code callerScopes} is an authorization predicate, not a filter ({@code null} = unscoped
   * caller, tenant-wide); {@link ScopeGuardedUpsert} says why the door's earlier check is not enough
   * on its own, and do not "simplify" this one away on the grounds that the door already asked.
   *
   * <p>It stamps the owner ({@link ManifestOwner#of}) and never re-homes another owner's row: that is {@code
   * NameTaken}. Which predicate refused is asked in the same transaction: {@code ON CONFLICT DO UPDATE} locks the
   * conflicting row even when its {@code WHERE} fails, so no delete and re-apply can change the answer meanwhile.
   */
  public ScopeGuardedUpsert upsert(long tenant, String name, String scope, String title, String yaml,
                                   Set<String> callerScopes) {
    // the store owns case normalization on BOTH sides (find lowercases too) — every
    // caller reaches the same row, the EntityRegistry rule
    String key = name.toLowerCase(Locale.ROOT);
    // The predicate reads the EXISTING row — which in a DO UPDATE is the one spelled with the table
    // name, `excluded` being the row we proposed.
    Long owner = ManifestOwner.of(tenant);
    return Jdbc.transaction(adminDs.get(), "SavedViewStore.upsert", c -> {
      int written = Jdbc.update(c, "SavedViewStore.upsert",
          "INSERT INTO connector.saved_view (name, scope, title, yaml_text, owner_tenant_id) VALUES (?,?,?,?,?) "
              + "ON CONFLICT (name) DO UPDATE SET scope = EXCLUDED.scope, "
              + "title = EXCLUDED.title, yaml_text = EXCLUDED.yaml_text, updated_at = now() "
              + "WHERE connector.saved_view.owner_tenant_id IS NOT DISTINCT FROM EXCLUDED.owner_tenant_id "
              + "AND (?::text[] IS NULL OR connector.saved_view.scope = ANY (?::text[]))",
          ps -> {
            ps.setString(1, key); ps.setString(2, scope); ps.setString(3, title); ps.setString(4, yaml);
            ManifestOwner.bind(ps, 5, owner);
            ScopeGuardedUpsert.bindGrants(ps, 6, callerScopes);
          });
      if (written > 0) return new ScopeGuardedUpsert.Applied();
      return Jdbc.exists(c, "SavedViewStore.upsert", "SELECT 1 FROM connector.saved_view "
          + "WHERE name = ? AND " + ManifestOwner.OWNED_ELSEWHERE, ps -> {
            ps.setString(1, key);
            ManifestOwner.bind(ps, 2, owner);
          }) ? new ScopeGuardedUpsert.NameTaken() : new ScopeGuardedUpsert.NameHeldOutOfScope();
    });
  }

  /** The views this tenant reads; scopes == null → all (unscoped user); empty set → none (bound to nothing). */
  public List<Summary> list(long tenant, Set<String> scopes) {
    if (scopes != null && scopes.isEmpty()) return List.of();
    // Two whole statements rather than one with a conditionally-appended WHERE: each placeholder
    // now sits in the same expression as the value bound to it. That is what a reader checks, and
    // it is what a static analyser can follow — the assembled form read as a bind against a
    // parameterless statement (S2695 BLOCKER, a false positive Sonar had no way to disprove).
    if (scopes == null) {
      return Jdbc.query(adminDs.get(), "SavedViewStore.list",
          "SELECT name, scope, title FROM connector.saved_view WHERE " + ManifestOwner.READS + " ORDER BY name",
          ps -> ps.setLong(1, tenant), SavedViewStore::summary);
    }
    return Jdbc.query(adminDs.get(), "SavedViewStore.list",
        "SELECT name, scope, title FROM connector.saved_view WHERE scope = ANY (?) AND " + ManifestOwner.READS
            + " ORDER BY name",
        ps -> {
          ps.setArray(1, ps.getConnection().createArrayOf("text", scopes.toArray()));
          ps.setLong(2, tenant);
        },
        SavedViewStore::summary);
  }

  private static Summary summary(ResultSet rs) throws SQLException {
    return new Summary(rs.getString(1), rs.getString(2), rs.getString(3));
  }

  /**
   * The scope of the row this name addresses, or empty when the name is free or this tenant does not read it (the
   * write then answers {@code NameTaken}). The apply door's
   * re-homing check ({@code ApplyScopeBoundary}) — the whole row is not wanted there, only who owns
   * the name, and {@link #find} would drag the yaml along for a question about one column.
   * Lowercased on read like {@link #find}, because {@link #upsert} lowercases on write.
   */
  public Optional<String> findScope(long tenant, String name) {
    return Jdbc.queryOne(adminDs.get(), "SavedViewStore.findScope",
        "SELECT scope FROM connector.saved_view WHERE name = ? AND " + ManifestOwner.READS,
        ps -> {
          ps.setString(1, name.toLowerCase(Locale.ROOT));
          ps.setLong(2, tenant);
        },
        rs -> rs.getString(1));
  }

  /** The view by name, when this tenant reads it. */
  public Optional<Row> find(long tenant, String name) {
    // lowercase on read like EntityRegistry.find — apply lowercases on write, so a
    // hand-typed 'Clients-Desk' must reach the same row as 'clients-desk'
    return Jdbc.queryOne(adminDs.get(), "SavedViewStore.find",
        "SELECT name, scope, title, yaml_text FROM connector.saved_view WHERE name = ? AND " + ManifestOwner.READS,
        ps -> {
          ps.setString(1, name.toLowerCase(Locale.ROOT));
          ps.setLong(2, tenant);
        },
        rs -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
  }

  /**
   * Removes one saved view for good — the delete gesture #487 gave kind:Process, missing here
   * (#507). Hard delete: {@code connector.saved_view} carries no {@code enabled} column, so
   * there is no reversible half the way kind:Process/kind:Workflow have one. Only a view this tenant owns: a shared
   * one is the hub's, deleted from the hub's own tenant.
   */
  public boolean delete(long tenant, String name) {
    return Jdbc.update(adminDs.get(), "SavedViewStore.delete",
        "DELETE FROM connector.saved_view WHERE name = ? AND " + ManifestOwner.OWNED_BY,
        ps -> {
          ps.setString(1, name.toLowerCase(Locale.ROOT));
          ManifestOwner.bind(ps, 2, ManifestOwner.of(tenant));
        }) > 0;
  }
}
