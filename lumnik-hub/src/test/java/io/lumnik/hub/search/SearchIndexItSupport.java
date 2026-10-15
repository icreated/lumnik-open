package io.lumnik.hub.search;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.testsupport.EntitySourceTableFixture;

import java.util.List;
import java.util.Locale;

/**
 * Shared fixtures for the #669 {@code kind: SearchIndex} ITs: an Entity manifest with its physical
 * source table, a SearchIndex manifest, and direct reads of {@code core.search_index} /
 * {@code core.search_index_purge} on the admin pool (BYPASSRLS — the setup and the oracle, never the
 * thing under test).
 */
final class SearchIndexItSupport {

    private SearchIndexItSupport() {}

    /**
     * Creates {@code connector.<table>} with one text column per canonical column ({@code src_<col>})
     * and returns an Entity manifest mapping them, matched on the first column.
     */
    static String entity(AgroalDataSource adminDs, String name, String table, List<String> scopes,
                         String... canonicalColumns) throws Exception {
        String[] sourceColumns = new String[canonicalColumns.length];
        for (int i = 0; i < canonicalColumns.length; i++) sourceColumns[i] = "src_" + canonicalColumns[i];
        EntitySourceTableFixture.create(adminDs, table, "v_" + name.toLowerCase(Locale.ROOT), sourceColumns);
        return entityManifest(name, table, scopes, canonicalColumns);
    }

    /** The Entity manifest alone — for a re-apply over a source table that already exists. */
    static String entityManifest(String name, String table, List<String> scopes, String... canonicalColumns) {
        StringBuilder map = new StringBuilder();
        for (String col : canonicalColumns) map.append("      src_").append(col).append(": ").append(col).append('\n');
        return "kind: Entity\n"
            + "name: " + name + "\n"
            + "match: [" + canonicalColumns[0] + "]\n"
            + "scopes: [" + String.join(", ", scopes) + "]\n"
            + "sources:\n"
            + "  - table: " + table + "\n"
            + "    map:\n"
            + map;
    }

    /** A SearchIndex over {@code entity}: the first field fuzzy-searchable, the others return-only. */
    static String searchIndex(String name, String entity, String... fields) {
        StringBuilder spec = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            spec.append("    - name: ").append(fields[i]).append('\n');
            if (i == 0) spec.append("      match: fuzzy\n");
        }
        return "apiVersion: search.lumnik.io/v1\n"
            + "kind: SearchIndex\n"
            + "metadata:\n"
            + "  name: " + name + "\n"
            + "spec:\n"
            + "  entity: " + entity + "\n"
            + "  fields:\n"
            + spec;
    }

    /**
     * One SearchIndex per Entity (#832): a test class that shares an entity across its tests, each declaring
     * a uniquely named index, must start every test with the entity free.
     */
    static void freeEntities(AgroalDataSource adminDs, String... entities) {
        for (String entity : entities) {
            Jdbc.update(adminDs, "SearchIndexItSupport.freeEntities",
                "DELETE FROM core.search_index WHERE lower(entity) = lower(?)", ps -> ps.setString(1, entity));
        }
    }

    static long count(AgroalDataSource adminDs, String sql, Object... params) {
        return Jdbc.queryOne(adminDs, "SearchIndexItSupport.count", sql,
            ps -> { for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]); },
            rs -> rs.getLong(1)).orElseThrow();
    }

    static long indexRows(AgroalDataSource adminDs, long tenant, String name) {
        return count(adminDs, "SELECT count(*) FROM core.search_index WHERE tenant_id = ? AND name = ?", tenant, name);
    }

    static long purgeRows(AgroalDataSource adminDs, long tenant, String name) {
        return count(adminDs, "SELECT count(*) FROM core.search_index_purge WHERE tenant_id = ? AND name = ?",
            tenant, name);
    }

    static long auditRows(AgroalDataSource adminDs, String name, String operation) {
        return count(adminDs, "SELECT count(*) FROM core.audit_outbox "
            + "WHERE entity_type = 'SearchIndex' AND entity_key = ? AND operation = ?", name, operation);
    }
}
