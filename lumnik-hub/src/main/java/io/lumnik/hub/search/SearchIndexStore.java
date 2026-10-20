package io.lumnik.hub.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.extraction.spi.search.ProjectionDefinition;
import io.lumnik.hub.api.ScopeGuardedUpsert;
import io.lumnik.hub.entity.EntityDefinitionLock;
import io.lumnik.hub.entity.EntityRegistry;
import io.lumnik.hub.search.mark.SearchProjectionMarkStore;
import io.lumnik.hub.search.projection.ProjectionDefinitions;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.hibernate.Session;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tenant-bound {@code kind: SearchIndex} declarations (#669), on the request's own RLS-enforced
 * connection — the {@code ExportManifestStore} posture, for the same reason: tenant isolation is a
 * property of the connection, and {@code SearchIndexTenantIsolationIT} proves it under
 * {@code RlsEnforcedProfile}. Every method but {@link #presentations} is {@code @Transactional(MANDATORY)}: without a
 * transaction SET LOCAL never fires and RLS fails closed in a way that reads like a 404.
 *
 * <p>Unlike Export, the scope that guards a name is not stored on the row: it is the referenced
 * entity's scopes, which live on the deployment-global {@code connector.entity_manifest}. Both
 * writes therefore re-ask the scope guard atomically by joining that row — containment, and a
 * non-empty set, exactly as {@code EntityViewCompiler} asks it ({@link ScopeGuardedUpsert}).
 * {@code entity_manifest.name} is stored lower-cased, so every join folds only this side.
 */
@ApplicationScoped
public class SearchIndexStore {

    /** One declaration as the list door shows it; {@code entityScopes} is empty when the entity is gone. */
    public record Summary(String name, String entity, String language, String status, String invalidReason,
                          boolean withheld, List<String> entityScopes) {}

    /** What an apply did. Two predicates guard it, so zero rows is disambiguated, not guessed. */
    public enum ApplyOutcome { APPLIED, NAME_HELD_OUT_OF_SCOPE, ENTITY_CHANGED, ENTITY_ALREADY_INDEXED }

    /** An apply's outcome; {@code holder} is the index that holds the entity, set only for ENTITY_ALREADY_INDEXED. */
    public record Applied(ApplyOutcome outcome, String holder) {
        static Applied of(ApplyOutcome outcome) {
            return new Applied(outcome, null);
        }
    }

    /** What a delete did: removed (one purge obligation written), no such name, or refused by the guard. */
    public enum DeleteOutcome { DELETED, NOT_FOUND, SCOPE_REFUSED }

    /** One (tenant, name) declaration's full detail — entity, manifest and scopes — for #703's
     *  operations door: everything triggerRebuild's own authorized-snapshot comparison needs, plus what
     *  the 404/403/409-invalid checks need. A brand new method (not reusing Summary/list): the wire
     *  shape of the existing registry door stays untouched. */
    public record Declaration(String entity, String manifestYaml, String status, String invalidReason,
                              boolean withheld, List<String> entityScopes) {}

    /** The caller's grants hold every scope of entity row {@code alias}, and it has at least one. */
    private static String held(String alias) {
        return "(cardinality(" + alias + ".scopes) > 0 AND " + alias + ".scopes <@ ?::text[])";
    }

    /**
     * The guard over the entity a stored row names; {@code ?::text[] IS NULL} is the unscoped caller.
     * Two placeholders, bound by {@link ScopeGuardedUpsert#bindGrants}.
     */
    private static final String GUARD =
        "(?::text[] IS NULL OR EXISTS (SELECT 1 FROM connector.entity_manifest e "
      + "WHERE e.name = lower(core.search_index.entity) AND " + held("e") + "))";

    @Inject EntityManager em;
    @Inject SearchProjectionMarkStore mark;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    private Session session() {
        return em.unwrap(Session.class);
    }

    /**
     * Insert or replace this tenant's declaration, bound to the entity version the door validated.
     *
     * <p>Two guards, both inside the statement. The INSERT's own {@code SELECT … WHERE} proceeds only
     * while the entity still has exactly {@code entityYaml} AND the caller holds every one of its
     * scopes — so a concurrent re-apply of the entity (another instance moving it out of the caller's
     * scopes, or dropping a column) writes nothing. The conflict arm adds {@link #GUARD} over the entity
     * the name CURRENTLY indexes, so a re-apply cannot take over a declaration held elsewhere. A re-apply
     * always comes back {@code valid}: only an apply that passed the door's checks reaches this method.
     *
     * <p>Both predicates read committed state; {@link EntityDefinitionLock} (shared, to commit) is what
     * keeps an entity re-apply from committing — and running its re-check — around this write.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public Applied upsert(long tenantId, String name, String entity, String entityYaml, String language,
                               String yaml, String manifestJson, ProjectionDefinition declared, Long actor,
                               Set<String> callerScopes) {
        return session().doReturningWork(conn -> {
            // Held to this transaction's commit: an entity re-apply waits for this write, and this write
            // waits for a compile in flight, then re-reads the committed definition below.
            EntityDefinitionLock.shared(conn, entity);
            // One SearchIndex per Entity (#832): serialized per (tenant, entity) so the holder read cannot be
            // stale. V61's unique index is only the backstop — its violation would abort this joined
            // transaction (a savepoint rollback is refused while enlisted), so it cannot become a 409.
            lockEntitySlot(conn, tenantId, entity);
            Optional<String> holder = holderOtherThan(conn, tenantId, entity, name);
            if (holder.isPresent()) return new Applied(ApplyOutcome.ENTITY_ALREADY_INDEXED, holder.get());
            boolean withheld = withholds(conn, tenantId, name, declared);
            if (Jdbc.update(conn, "SearchIndexStore.upsert",
                    "INSERT INTO core.search_index "
                  + "(tenant_id, name, entity, language, manifest_yaml, manifest, created_by, updated_by, withheld) "
                  + "SELECT ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ? "
                  + "WHERE EXISTS (SELECT 1 FROM connector.entity_manifest n "
                  + "  WHERE n.name = lower(?) AND n.yaml_text = ? AND (?::text[] IS NULL OR " + held("n") + ")) "
                  + "ON CONFLICT (tenant_id, name) DO UPDATE SET "
                  + "entity = excluded.entity, language = excluded.language, "
                  + "manifest_yaml = excluded.manifest_yaml, manifest = excluded.manifest, "
                  + "status = 'valid', invalid_reason = NULL, withheld = excluded.withheld, "
                  + "updated_at = now(), updated_by = excluded.updated_by "
                  + "WHERE " + GUARD,
                    ps -> {
                        ps.setLong(1, tenantId);
                        ps.setString(2, name);
                        ps.setString(3, entity);
                        ps.setString(4, language);
                        ps.setString(5, yaml);
                        ps.setString(6, manifestJson);
                        setActor(ps, 7, actor);
                        setActor(ps, 8, actor);
                        ps.setBoolean(9, withheld);
                        ps.setString(10, entity);
                        ps.setString(11, entityYaml);
                        ScopeGuardedUpsert.bindGrants(ps, 12, callerScopes);
                        ScopeGuardedUpsert.bindGrants(ps, 14, callerScopes);
                    }) == 1) {
                // Trigger 4a (design §4, row 4a): a fresh apply or a changed declaration has no
                // prior projected set to diff against — full re-project is the only sound option.
                // Same transaction as the upsert itself: a mark-write failure fails the apply, same
                // as any other write in this method would.
                mark.markAll(conn, tenantId, name, entity, "index-apply");
                return Applied.of(ApplyOutcome.APPLIED);
            }
            // Nothing written: the entity moved since the door read it, or the name is held elsewhere.
            return Jdbc.exists(conn, "SearchIndexStore.upsert(entity unchanged?)",
                    "SELECT 1 FROM connector.entity_manifest WHERE name = lower(?) AND yaml_text = ?",
                    ps -> {
                        ps.setString(1, entity);
                        ps.setString(2, entityYaml);
                    })
                ? Applied.of(ApplyOutcome.NAME_HELD_OUT_OF_SCOPE) : Applied.of(ApplyOutcome.ENTITY_CHANGED);
        });
    }

    /**
     * Whether re-applying {@code name} as {@code declared} leaves it withheld (#1219): it already was — only a
     * rebuild releases it — or {@code declared} narrows the stored declaration. FOR UPDATE: concurrent re-applies
     * of one name each compare against what the other wrote.
     */
    private static boolean withholds(Connection conn, long tenantId, String name, ProjectionDefinition declared) {
        return Jdbc.queryOne(conn, "SearchIndexStore.withholds",
                "SELECT manifest_yaml, withheld FROM core.search_index WHERE tenant_id = ? AND name = ? FOR UPDATE",
                ps -> {
                    ps.setLong(1, tenantId);
                    ps.setString(2, name);
                },
                rs -> rs.getBoolean(2) || ProjectionDefinitions.fromStored(rs.getString(1))
                    .map(stored -> ProjectionDefinitions.narrows(stored, declared)).orElse(true))
            .orElseGet(() -> purgePending(conn, tenantId, name));
    }

    /** A name deleted but not yet purged: its old build may still answer under it, so a declaration re-applied
     *  under that name is no fresh index — what that build serves cannot be compared, only withheld. */
    private static boolean purgePending(Connection conn, long tenantId, String name) {
        return Jdbc.exists(conn, "SearchIndexStore.purgePending",
                "SELECT 1 FROM core.search_index_purge WHERE tenant_id = ? AND name = ?",
                ps -> {
                    ps.setLong(1, tenantId);
                    ps.setString(2, name);
                });
    }

    private static void lockEntitySlot(Connection conn, long tenantId, String entity) {
        // The lock is the statement's effect; its one (void) row is read and dropped.
        Jdbc.queryOne(conn, "SearchIndexStore.lockEntitySlot", "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
            ps -> ps.setString(1, "core.search_index:" + tenantId + ":" + entity.toLowerCase(Locale.ROOT)),
            rs -> true);
    }

    /** The declaration over {@code entity} whose name is not {@code except}. */
    private static Optional<String> holderOtherThan(Connection conn, long tenantId, String entity, String except) {
        return Jdbc.queryOne(conn, "SearchIndexStore.holderOtherThan",
                "SELECT name FROM core.search_index WHERE tenant_id = ? AND lower(entity) = lower(?) "
              + "AND name <> ?",
                ps -> {
                    ps.setLong(1, tenantId);
                    ps.setString(2, entity);
                    ps.setString(3, except);
                }, rs -> rs.getString(1));
    }

    /**
     * The scopes of the entity this (tenant, name) currently indexes, or empty when the name is free —
     * the apply and delete doors' ordered re-homing check. A stored row whose entity no longer exists
     * answers an empty list, which a bound caller is refused ({@code allowsAll} needs a tag).
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public Optional<List<String>> storedEntityScopes(long tenantId, String name) {
        return session().doReturningWork(conn -> Jdbc.queryOne(conn, "SearchIndexStore.storedEntityScopes",
                "SELECT e.scopes FROM core.search_index s "
              + "LEFT JOIN connector.entity_manifest e ON e.name = lower(s.entity) "
              + "WHERE s.tenant_id = ? AND s.name = ?",
                ps -> {
                    ps.setLong(1, tenantId);
                    ps.setString(2, name);
                }, rs -> EntityRegistry.scopes(rs.getArray(1))));
    }

    /** Every declaration of this tenant with its entity's scopes; the door filters them by grants. */
    @Transactional(Transactional.TxType.MANDATORY)
    public List<Summary> list(long tenantId) {
        return session().doReturningWork(conn -> Jdbc.query(conn, "SearchIndexStore.list",
                "SELECT s.name, s.entity, s.language, s.status, s.invalid_reason, s.withheld, e.scopes "
              + "FROM core.search_index s "
              + "LEFT JOIN connector.entity_manifest e ON e.name = lower(s.entity) "
              + "WHERE s.tenant_id = ? ORDER BY s.name",
                ps -> ps.setLong(1, tenantId),
                rs -> new Summary(rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getString(5), rs.getBoolean(6), EntityRegistry.scopes(rs.getArray(7)))));
    }

    /** What a client needs to draw one hit of an index beyond the provider's answer: the title field (#1044)
     *  and the external links (#1051). Either may be absent; an index with neither has no entry at all. */
    public record HitPresentation(String titleField, List<SearchIndexManifest.Link> links) {}

    private static final ObjectMapper JSON = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final TypeReference<List<SearchIndexManifest.Link>> LINKS = new TypeReference<>() {};

    /**
     * The {@link HitPresentation} of each named index of this tenant that declares a {@code titleField} or
     * {@code links}; an index with neither, or an unknown name, is simply absent. Read after the provider
     * answered; plain REQUIRED, so it joins a caller's transaction if there is one. Its one caller, the
     * search resource, has none (a search never holds one open around the provider call), so here it opens
     * its own, briefly.
     */
    @Transactional
    public Map<String, HitPresentation> presentations(long tenantId, Collection<String> indexNames) {
        if (indexNames.isEmpty()) return Map.of();
        return session().doReturningWork(conn -> Jdbc.query(conn, "SearchIndexStore.presentations",
                    "SELECT name, manifest->>'titleField', (manifest->'links')::text FROM core.search_index "
                  + "WHERE tenant_id = ? AND name = ANY(?) "
                  + "AND (manifest->>'titleField' IS NOT NULL OR manifest->'links' IS NOT NULL)",
                    ps -> {
                        ps.setLong(1, tenantId);
                        ps.setArray(2, conn.createArrayOf("text", indexNames.toArray()));
                    },
                    rs -> Map.entry(rs.getString(1), new HitPresentation(rs.getString(2), links(rs.getString(3)))))
            .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    /**
     * The entities, lower-cased, whose SearchIndex of this tenant must not answer: an {@code invalid} declaration
     * (#1187), or one {@code withheld} because its build still serves a field it no longer declares (#1219). One
     * index per entity (#832) makes the entity the unit to leave out.
     * Plain REQUIRED, like {@link #presentations}: the search resource holds no transaction of its own.
     */
    @Transactional
    public Set<String> unservedEntities(long tenantId) {
        return session().doReturningWork(conn -> new HashSet<>(Jdbc.query(conn, "SearchIndexStore.unservedEntities",
                "SELECT lower(entity) FROM core.search_index WHERE tenant_id = ? "
              + "AND (status = 'invalid' OR withheld)",
                ps -> ps.setLong(1, tenantId), rs -> rs.getString(1))));
    }

    /** Links are cosmetic: a stored value that is null, JSON null or unreadable is "no links" for that index —
     *  it must never cost the other indexes of the same answer their titles. */
    static List<SearchIndexManifest.Link> links(String json) {
        if (json == null) return List.of();
        try {
            List<SearchIndexManifest.Link> links = JSON.readValue(json, LINKS);
            return links == null ? List.of() : links;
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    /**
     * One tenant's full declaration detail by name — #703's operations door (Task 9), which needs
     * the manifest and invalid-reason the list/summary doors never expose.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public Optional<Declaration> get(long tenantId, String name) {
        return session().doReturningWork(conn -> Jdbc.queryOne(conn, "SearchIndexStore.get",
                "SELECT s.entity, s.manifest_yaml, s.status, s.invalid_reason, s.withheld, e.scopes "
              + "FROM core.search_index s "
              + "LEFT JOIN connector.entity_manifest e ON e.name = lower(s.entity) "
              + "WHERE s.tenant_id = ? AND s.name = ?",
                ps -> {
                    ps.setLong(1, tenantId);
                    ps.setString(2, name);
                },
                rs -> new Declaration(rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getBoolean(5), EntityRegistry.scopes(rs.getArray(6)))));
    }

    /**
     * Removes the declaration and records its purge obligation in ONE statement: the obligation exists
     * if and only if the delete happened. The scope guard sits in the DELETE's own WHERE, so a
     * concurrent re-apply onto an entity outside the caller's grants cannot slip between check and
     * delete.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public DeleteOutcome deleteWithPurge(long tenantId, String name, Long actor, Set<String> callerScopes) {
        return session().doReturningWork(conn -> {
            if (Jdbc.update(conn, "SearchIndexStore.deleteWithPurge",
                    "WITH gone AS ("
                  + "  DELETE FROM core.search_index WHERE tenant_id = ? AND name = ? AND " + GUARD
                  + "  RETURNING tenant_id, name, entity) "
                  + "INSERT INTO core.search_index_purge (tenant_id, name, entity, requested_by) "
                  + "SELECT tenant_id, name, entity, ? FROM gone",
                    ps -> {
                        ps.setLong(1, tenantId);
                        ps.setString(2, name);
                        ScopeGuardedUpsert.bindGrants(ps, 3, callerScopes);
                        setActor(ps, 5, actor);
                    }) == 1) return DeleteOutcome.DELETED;
            // Nothing deleted. The door already 404'd a missing name, so NOT_FOUND here is only the race
            // where a concurrent delete won; a row still present means the guard refused it.
            return Jdbc.exists(conn, "SearchIndexStore.deleteWithPurge(still there?)",
                    "SELECT 1 FROM core.search_index WHERE tenant_id = ? AND name = ?",
                    ps -> {
                        ps.setLong(1, tenantId);
                        ps.setString(2, name);
                    })
                ? DeleteOutcome.SCOPE_REFUSED : DeleteOutcome.NOT_FOUND;
        });
    }

    /**
     * Trigger 6 (design §4, row 6): purges every tenant's declaration over {@code entity} in one
     * statement, the same {@code deleteWithPurge} CTE shape widened from one {@code (tenant, name)}
     * pair to every one over this entity. Entity manifests are deployment-global, so this runs on the
     * ADMIN datasource — cross-tenant, unlike every other method here which is request-scoped on the
     * RLS-enforced {@code default} connection. No scope guard: this follows an already-authorized
     * entity delete as a system action, exactly like {@link SearchIndexRevalidator}'s cross-tenant
     * invalidation needs none.
     *
     * @return the number of declarations purged (0 when nothing indexed this entity)
     */
    public int purgeAllForEntity(String entity, Long actor) {
        return Jdbc.update(adminDs, "SearchIndexStore.purgeAllForEntity", PURGE_ALL_FOR_ENTITY_SQL,
            ps -> bindPurgeAllForEntity(ps, entity, actor));
    }

    /**
     * The same purge, on a connection the caller already holds open in ITS OWN transaction — so the
     * purge commits or rolls back atomically WITH whatever that transaction also does (design §4 row
     * 6's actual requirement: a review of this PR found the no-{@code Connection} overload above
     * committing on its own, ahead of {@code EntityViewCompiler.delete}'s separate transaction, which
     * left a window where the purge had already landed but the entity's view/manifest row had not
     * actually been dropped yet — {@code EntityWriteResource.delete} now calls this overload from
     * inside {@link io.lumnik.hub.entity.EntityViewCompiler#delete(String,
     * io.lumnik.hub.entity.EntityViewCompiler.ConnectionWork)}'s own connection instead).
     */
    public void purgeAllForEntity(Connection c, String entity, Long actor) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(PURGE_ALL_FOR_ENTITY_SQL)) {
            bindPurgeAllForEntity(ps, entity, actor);
            ps.executeUpdate();
        }
    }

    /**
     * The audit row of each declaration it removes rides the same statement (#1193), in the tenant that
     * owned it: the cascade commits with its trace or not at all, and a tenant's trail shows a
     * SearchIndex another tenant's entity delete took — the same row {@code DELETE /api/search-indexes}
     * writes, plus the entity that caused it.
     */
    private static final String PURGE_ALL_FOR_ENTITY_SQL =
        "WITH gone AS ("
      + "  DELETE FROM core.search_index WHERE lower(entity) = lower(?) "
      + "  RETURNING tenant_id, name, entity), "
      + "purge AS ("
      + "  INSERT INTO core.search_index_purge (tenant_id, name, entity, requested_by) "
      + "  SELECT tenant_id, name, entity, ? FROM gone) "
      + "INSERT INTO core.audit_outbox (tenant_id, actor_id, entity_type, entity_key, operation, changes) "
      + "SELECT tenant_id, ?, 'SearchIndex', name, 'DELETE', "
      + "  jsonb_build_object('purge', 'requested', 'entity_deleted', entity) FROM gone";

    private static void bindPurgeAllForEntity(PreparedStatement ps, String entity, Long actor) throws SQLException {
        ps.setString(1, entity);
        setActor(ps, 2, actor);
        setActor(ps, 3, actor);
    }

    /**
     * Trigger 7 (design §4, row 7): purges every SearchIndex this tenant owns, across every entity,
     * in one statement — the same CTE shape as {@link #purgeAllForEntity(String, Long)}, widened
     * from {@code entity} to {@code tenant_id}. Runs on the ADMIN datasource: called from
     * {@code TenantArchivedSearchPurgeListener}, which has no RLS-scoped request connection of its
     * own to run on (the firing request is identity's, a different module's transaction). The
     * {@code tenant_id} predicate is what makes "a purge that cannot touch another tenant" true by
     * construction, the same guarantee RLS gives every other write in this class.
     *
     * @return the number of declarations purged (0 when the tenant held none)
     */
    public int purgeAllForTenant(long tenantId, Long actor) {
        return Jdbc.update(adminDs, "SearchIndexStore.purgeAllForTenant",
            "WITH gone AS ("
          + "  DELETE FROM core.search_index WHERE tenant_id = ? "
          + "  RETURNING tenant_id, name, entity) "
          + "INSERT INTO core.search_index_purge (tenant_id, name, entity, requested_by) "
          + "SELECT tenant_id, name, entity, ? FROM gone",
            ps -> {
                ps.setLong(1, tenantId);
                setActor(ps, 2, actor);
            });
    }

    private static void setActor(PreparedStatement ps, int index, Long actor) throws SQLException {
        if (actor == null) ps.setNull(index, Types.BIGINT); else ps.setLong(index, actor);
    }
}
