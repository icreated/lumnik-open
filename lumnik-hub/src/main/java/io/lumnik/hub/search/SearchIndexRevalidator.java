package io.lumnik.hub.search;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.entity.EntityDefinitionLock;
import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.search.mark.SearchProjectionMarkStore;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Re-checks every tenant's SearchIndexes over an entity that was just (re-)applied (#669). Entity
 * manifests are deployment-global, so this runs on the admin pool across tenants: a column the entity
 * no longer has turns the declaration {@code invalid}, with the cross-check's own message as reason.
 *
 * <p>It never fails the Entity apply — Search is optional, and the Entity door must not depend on it.
 * It only ever invalidates: a declaration becomes {@code valid} again through a SearchIndex re-apply
 * that passes, never because the entity grew the column back.
 *
 * <p><b>Read, then write — optimistically.</b> A re-apply, or a delete and re-creation, can replace a
 * declaration between the sweep's read and its update. The update therefore names the exact version
 * it read (row {@code id}, which a re-creation changes, and {@code updated_at}, which every upsert
 * bumps): a replaced declaration is left alone, never invalidated with a reason computed on YAML that
 * is no longer its own. The replacement was itself checked at its apply, against the current entity.
 * The same holds for the entity side: the update also requires the entity to still be the exact
 * definition the callback was fired for, under {@link EntityDefinitionLock} held shared to its commit.
 */
@ApplicationScoped
public class SearchIndexRevalidator {

    private static final Logger LOG = Logger.getLogger(SearchIndexRevalidator.class);

    @Inject @DataSource("admin") AgroalDataSource ds;
    @Inject SearchProjectionMarkStore mark;

    private final SearchIndexManifestParser parser = new SearchIndexManifestParser();

    /** One declaration as the sweep read it — the version its invalidation is conditional on. */
    record Declared(long tenantId, String name, UUID id, Timestamp updatedAt, String yaml) {}

    /**
     * Best-effort by contract: a failure is logged, never thrown into the Entity door.
     *
     * @param entityYaml the exact definition this callback was fired for; an invalidation only lands
     *                   while it is still the stored one, so a callback that stalls past a newer
     *                   re-apply cannot invalidate against a definition that no longer exists.
     */
    public void afterEntityApplied(EntityManifest entity, String entityYaml) {
        try {
            for (Declared d : snapshot(entity.name())) {
                Optional<String> problem = SearchIndexDefinitionCheck.problem(parser.parse(d.yaml()), entity);
                if (problem.isPresent()) {
                    if (invalidateIfUnchanged(d, problem.get(), entity.name(), entityYaml))
                        LOG.infof("SearchIndex '%s' (tenant %d) is now invalid: %s", d.name(), d.tenantId(), problem.get());
                } else {
                    // Trigger 2 (design §4, row 2): the declaration is still valid after the re-check —
                    // its projected documents may be stale against the new definition, so mark a full
                    // re-project. Best-effort like the rest of this sweep: a failure here is caught by
                    // afterEntityApplied's own try/catch, not thrown into the Entity door.
                    mark.markAll(d.tenantId(), d.name(), entity.name(), "entity-reapply");
                }
            }
        } catch (Exception e) {
            LOG.warnf(e, "SearchIndex re-check after entity '%s' apply failed", entity.name());
        }
    }

    /** Every tenant's valid declaration over this entity, with the version each was read at. */
    List<Declared> snapshot(String entityName) {
        return Jdbc.query(ds, "SearchIndexRevalidator.snapshot",
            "SELECT tenant_id, name, id, updated_at, manifest_yaml FROM core.search_index "
          + "WHERE lower(entity) = lower(?) AND status = 'valid'",
            ps -> ps.setString(1, entityName),
            rs -> new Declared(rs.getLong(1), rs.getString(2), rs.getObject(3, UUID.class),
                rs.getTimestamp(4), rs.getString(5)));
    }

    /**
     * Invalidates the declaration only if it is still exactly the version read AND the entity is still
     * exactly {@code entityYaml} — both versions the reason was computed from; true when it did.
     *
     * <p>Its own transaction, holding {@link EntityDefinitionLock} shared from the entity check to the
     * commit: the next re-apply of the entity waits for this invalidation, and an invalidation that
     * waited on a re-apply re-reads the committed definition — so it never commits against a definition
     * that has already moved on. Best-effort like the sweep: a failure rolls back and is rethrown.
     */
    boolean invalidateIfUnchanged(Declared d, String reason, String entityName, String entityYaml) {
        try (Connection c = ds.getConnection()) {
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                boolean invalidated = invalidateIfUnchanged(c, d, reason, entityName, entityYaml);
                c.commit();
                return invalidated;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SearchIndexRevalidator.invalidate: " + e.getMessage(), e);
        }
    }

    /** The same, inside the caller's open transaction on {@code c}; the lock is held to its commit. */
    boolean invalidateIfUnchanged(Connection c, Declared d, String reason, String entityName, String entityYaml)
            throws SQLException {
        EntityDefinitionLock.shared(c, entityName);
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE core.search_index SET status = 'invalid', invalid_reason = ?, updated_at = now() "
              + "WHERE tenant_id = ? AND name = ? AND id = ? AND updated_at = ? AND status = 'valid' "
              + "AND EXISTS (SELECT 1 FROM connector.entity_manifest e "
              + "  WHERE e.name = lower(core.search_index.entity) AND e.yaml_text = ?)")) {
            ps.setString(1, reason);
            ps.setLong(2, d.tenantId());
            ps.setString(3, d.name());
            ps.setObject(4, d.id());
            ps.setTimestamp(5, d.updatedAt());
            ps.setString(6, entityYaml);
            return ps.executeUpdate() == 1;
        }
    }
}
