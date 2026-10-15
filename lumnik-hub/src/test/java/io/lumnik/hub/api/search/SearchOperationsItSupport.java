package io.lumnik.hub.api.search;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.testsupport.EntitySourceTableFixture;

import java.util.Locale;

/**
 * Shared fixtures for #703's {@code /api/platform/search/*} ITs — {@code SearchIndexItSupport}'s own
 * entity/searchIndex YAML shapes, copied rather than shared: that class is package-private in {@code
 * io.lumnik.hub.search}, a different package from this door's own ({@code io.lumnik.hub.api.search}),
 * and {@code SearchProjectionFullRebuildIT}'s own javadoc already establishes that this kind of tiny
 * test-fixture duplication is the accepted norm here rather than a shared, over-general helper.
 */
final class SearchOperationsItSupport {

    private SearchOperationsItSupport() {}

    static String unique(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    /** Creates {@code connector.<table>} (code/name/city) and returns an Entity manifest over it,
     *  scoped {@code [sales]}, matched on {@code code} — entity manifests are deployment-global
     *  (CLAUDE.md), so this is applied once per test class, never per tenant. */
    static String entity(AgroalDataSource adminDs, String name, String table) throws Exception {
        EntitySourceTableFixture.create(adminDs, table, "v_" + name.toLowerCase(Locale.ROOT),
            "src_code", "src_name", "src_city");
        return "kind: Entity\n"
            + "name: " + name + "\n"
            + "match: [code]\n"
            + "scopes: [sales]\n"
            + "sources:\n"
            + "  - table: " + table + "\n"
            + "    map:\n"
            + "      src_code: code\n"
            + "      src_name: name\n"
            + "      src_city: city\n";
    }

    /** A SearchIndex over {@code entity}: {@code name} fuzzy-searchable, {@code city} return-only. */
    static String searchIndex(String name, String entity) {
        return "apiVersion: search.lumnik.io/v1\n"
            + "kind: SearchIndex\n"
            + "metadata:\n"
            + "  name: " + name + "\n"
            + "spec:\n"
            + "  entity: " + entity + "\n"
            + "  fields:\n"
            + "    - name: name\n"
            + "      match: fuzzy\n"
            + "    - name: city\n";
    }

    /** One SearchIndex per Entity (#832): a class sharing an entity across tests starts each with it free. */
    static void freeEntities(AgroalDataSource adminDs, String... entities) {
        for (String entity : entities) {
            Jdbc.update(adminDs, "SearchOperationsItSupport.freeEntities",
                "DELETE FROM core.search_index WHERE lower(entity) = lower(?)", ps -> ps.setString(1, entity));
        }
    }

    static long count(AgroalDataSource adminDs, String sql, Object... params) {
        return Jdbc.queryOne(adminDs, "SearchOperationsItSupport.count", sql,
            ps -> { for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]); },
            rs -> rs.getLong(1)).orElseThrow();
    }

    /** {@code SearchIndexItSupport}'s own helper, copied rather than shared (same posture as the rest
     *  of this class's own javadoc). */
    static long auditRows(AgroalDataSource adminDs, String name, String operation) {
        return count(adminDs, "SELECT count(*) FROM core.audit_outbox "
            + "WHERE entity_type = 'SearchIndex' AND entity_key = ? AND operation = ?", name, operation);
    }
}
