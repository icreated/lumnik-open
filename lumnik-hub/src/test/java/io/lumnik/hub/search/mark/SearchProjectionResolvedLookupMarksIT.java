package io.lumnik.hub.search.mark;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.entity.EntityRegistry;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.createTable;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.exec;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.fireRun;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.insertIdentity;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.insertUndecidedIdentity;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.markedKeys;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.resolutionJoinEntityYaml;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.resolutionJoinTwoSourceEntityYaml;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.searchIndex;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.unique;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.watermarkRowCount;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.yaml;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #850: a lookup change marks the RESOLVED ENTITIES whose sources carry the changed key — keyed by
 * {@code entity_id}, through {@code core.entity_identity}. Lookup tables are append-only: a changed row is a new
 * row, a byte-identical re-ingest only bumps {@code _ingested_at}; the tests model exactly those two events and
 * drain the marks between them (marks are unique on (index, key)).
 */
@QuarkusTest
class SearchProjectionResolvedLookupMarksIT {

    private static final String REASON = "lookup-change";

    @Inject SearchProjectionRunHook hook;
    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject EntityRegistry registry;
    @Inject SearchProjectionMarkStore markStore;
    @Inject SearchProjectionWatermarkStore watermarkStore;

    static final UUID E1 = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID E2 = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    static final UUID E3 = UUID.fromString("00000000-0000-0000-0000-0000000000a3");

    record Fx(long tenant, String entity, String products, String lookup, String index) {}

    /**
     * P1,P2 → C1 (decided: E1, E2) ; P3 → C2 (decided: E3) ; P4 → C1 and P6 → C3 (identity ambiguous / absent);
     * an empty lookup; one settled run. Only the lookup may mark from here on.
     */
    private Fx fixture(long tenant) throws Exception {
        String n = String.valueOf(System.nanoTime());
        String products = "t_rm_p_" + n, lookup = "t_rm_c_" + n, entity = "ResMarks" + n;
        createTable(adminDs, products, "pid text, cid text, pname text");
        createTable(adminDs, lookup, "cid text, cname text");
        yaml(tenant).body(resolutionJoinEntityYaml(entity, products, lookup, "category_name"))
            .post("/api/entities/apply").then().statusCode(200);
        String index = unique("res-marks");
        yaml(tenant).body(searchIndex(index, entity, "product_name")).post("/api/search-indexes/apply")
            .then().statusCode(200);
        for (String[] r : new String[][] {{"P1", "C1"}, {"P2", "C1"}, {"P3", "C2"}, {"P4", "C1"}, {"P6", "C3"}}) {
            exec(adminDs, "INSERT INTO connector." + products + " (tenant_id, pid, cid, pname) VALUES ("
                + tenant + ", '" + r[0] + "', '" + r[1] + "', 'n" + r[0] + "')");
        }
        insertIdentity(adminDs, tenant, entity, products, "P1", E1);
        insertIdentity(adminDs, tenant, entity, products, "P2", E2);
        insertIdentity(adminDs, tenant, entity, products, "P3", E3);
        insertUndecidedIdentity(adminDs, tenant, entity, products, "P4", "ambiguous");   // P6 has no crosswalk row at all
        fireRun(hook, tenant);                                             // settles the watermarks
        exec(adminDs, "UPDATE connector." + products + " SET _ingested_at = now() - interval '1 hour'");
        drain(tenant);
        return new Fx(tenant, entity, products, lookup, index);
    }

    private void drain(long tenant) throws Exception {
        exec(adminDs, "DELETE FROM core.search_projection_mark WHERE tenant_id = " + tenant);
    }

    private static List<String> ids(UUID... ids) {
        return Arrays.stream(ids).map(UUID::toString).sorted().toList();
    }

    @Test
    void the_first_lookup_row_for_a_key_marks_the_decided_entities_holding_it_and_only_them() throws Exception {
        Fx f = fixture(850_301);
        exec(adminDs, "INSERT INTO connector." + f.lookup() + " (tenant_id, cid, cname) VALUES (850301, 'C1', 'Pumps')");
        fireRun(hook, f.tenant());
        assertThat(markedKeys(adminDs, f.tenant(), f.index(), REASON))
            .as("E1 and E2 hold C1; E3 holds C2; P4 holds C1 but is not decided").containsExactlyElementsOf(ids(E1, E2));
    }

    @Test
    void one_more_row_of_different_content_for_the_key_marks_them_again() throws Exception {
        Fx f = fixture(850_302);
        exec(adminDs, "INSERT INTO connector." + f.lookup() + " (tenant_id, cid, cname) VALUES (850302, 'C1', 'Pumps')");
        fireRun(hook, f.tenant());
        drain(f.tenant());                                                 // the projection applied them
        // The first row is old news from here on: the watermark is capped one second behind the clock, so without this a
        // second scan would find the FIRST row again and "marks them again" could not be told from ignoring the second.
        exec(adminDs, "UPDATE connector." + f.lookup() + " SET _ingested_at = now() - interval '1 hour' WHERE tenant_id = 850302");
        exec(adminDs, "INSERT INTO connector." + f.lookup() + " (tenant_id, cid, cname) VALUES (850302, 'C1', 'Pompes')");
        fireRun(hook, f.tenant());
        assertThat(markedKeys(adminDs, f.tenant(), f.index(), REASON)).containsExactlyElementsOf(ids(E1, E2));
    }

    // Re-ingesting a byte-identical row hits ON CONFLICT (_row_hash) and only moves _ingested_at: that touch is what this
    // models. It is NOT a lookup duplicate: a genuine second row for the key (another _row_hash, even with equal joined
    // values) is a NEW row and makes the key ambiguous (slice 1) — see the "one more row" test above.
    @Test
    void a_touch_by_a_row_hash_conflict_moves_the_timestamp_and_remarks_harmlessly() throws Exception {
        Fx f = fixture(850_303);
        exec(adminDs, "INSERT INTO connector." + f.lookup() + " (tenant_id, cid, cname) VALUES (850303, 'C1', 'Pumps')");
        fireRun(hook, f.tenant());
        drain(f.tenant());
        // old news first (see the "one more row" test), then the touch itself: only the touch can make it scannable again
        exec(adminDs, "UPDATE connector." + f.lookup() + " SET _ingested_at = now() - interval '1 hour' WHERE tenant_id = 850303");
        exec(adminDs, "UPDATE connector." + f.lookup() + " SET _ingested_at = now() WHERE tenant_id = 850303");
        fireRun(hook, f.tenant());
        assertThat(markedKeys(adminDs, f.tenant(), f.index(), REASON)).containsExactlyElementsOf(ids(E1, E2));
    }

    // #864: the resolved reverse scan compares from/to by the same join-key rule as the compiled join; the match:
    // twin lives in SearchProjectionLookupMarksIT. 1000021 (bigint) and 1000021.0000 (numeric(18,4)) are one key.
    @Test
    void a_lookup_key_of_another_scale_still_marks_the_decided_entities_holding_it() throws Exception {
        long tenant = 850_304;
        String n = String.valueOf(System.nanoTime());
        String products = "t_rm_sc_p_" + n, lookup = "t_rm_sc_c_" + n, entity = "ResScale" + n;
        createTable(adminDs, products, "pid text, cid bigint, pname text");
        createTable(adminDs, lookup, "cid numeric(18,4), cname text");
        yaml(tenant).body(resolutionJoinEntityYaml(entity, products, lookup, "category_name"))
            .post("/api/entities/apply").then().statusCode(200);
        String index = unique("res-scale");
        yaml(tenant).body(searchIndex(index, entity, "product_name")).post("/api/search-indexes/apply")
            .then().statusCode(200);
        exec(adminDs, "INSERT INTO connector." + products + " (tenant_id, pid, cid, pname) VALUES ("
            + tenant + ", 'P1', 1000021, 'nP1'), (" + tenant + ", 'P2', 1000022, 'nP2')");
        insertIdentity(adminDs, tenant, entity, products, "P1", E1);
        insertIdentity(adminDs, tenant, entity, products, "P2", E2);
        fireRun(hook, tenant);                                             // settles the watermarks
        exec(adminDs, "UPDATE connector." + products + " SET _ingested_at = now() - interval '1 hour'");
        drain(tenant);

        exec(adminDs, "INSERT INTO connector." + lookup + " (tenant_id, cid, cname) VALUES (" + tenant + ", 1000021, 'Pompes')");
        fireRun(hook, tenant);

        assertThat(markedKeys(adminDs, tenant, index, REASON))
            .as("E1 holds 1000021, E2 holds 1000022").containsExactlyElementsOf(ids(E1));
    }

    private record TwoSourceFx(long tenant, String entity, String s1, String s2, String lookup, String index) {}

    /** An entity over two sources and one lookup, nothing inserted yet. */
    private TwoSourceFx twoSourceFixture(long tenant) throws Exception {
        String n = String.valueOf(System.nanoTime());
        String s1 = "t_rm2_a_" + n, s2 = "t_rm2_b_" + n, lookup = "t_rm2_c_" + n, entity = "ResTwoSrc" + n;
        createTable(adminDs, s1, "pid text, cid text, pname text");
        createTable(adminDs, s2, "pid text, cid text, pname text");
        createTable(adminDs, lookup, "cid text, cname text");
        yaml(tenant).body(resolutionJoinTwoSourceEntityYaml(entity, s1, s2, lookup, "category_name"))
            .post("/api/entities/apply").then().statusCode(200);
        String index = unique("res-two-src");
        yaml(tenant).body(searchIndex(index, entity, "product_name")).post("/api/search-indexes/apply")
            .then().statusCode(200);
        return new TwoSourceFx(tenant, entity, s1, s2, lookup, index);
    }

    private void row(TwoSourceFx f, String table, String pid, String cid) throws Exception {
        exec(adminDs, "INSERT INTO connector." + table + " (tenant_id, pid, cid, pname) VALUES ("
            + f.tenant() + ", '" + pid + "', '" + cid + "', 'n')");
    }

    private void settle(TwoSourceFx f) throws Exception {
        fireRun(hook, f.tenant());
        exec(adminDs, "UPDATE connector." + f.s1() + " SET _ingested_at = now() - interval '1 hour'");
        exec(adminDs, "UPDATE connector." + f.s2() + " SET _ingested_at = now() - interval '1 hour'");
        drain(f.tenant());
    }

    // The v1 cardinality is UNIQUE (tenant, entity_type, source, entity_id): rows of one SOURCE cannot converge, rows of
    // several sources can. Both hold C1; the entity is marked once and the scan does not fail on the repeated key. (The
    // mark store also de-duplicates its input, so this pins the behaviour, not the SQL DISTINCT.)
    @Test
    void rows_of_two_sources_converging_on_one_identity_mark_it_once() throws Exception {
        TwoSourceFx f = twoSourceFixture(850_305);
        row(f, f.s1(), "A", "C1");
        row(f, f.s2(), "B", "C1");
        insertIdentity(adminDs, f.tenant(), f.entity(), f.s1(), "A", E1);
        insertIdentity(adminDs, f.tenant(), f.entity(), f.s2(), "B", E1);
        settle(f);

        exec(adminDs, "INSERT INTO connector." + f.lookup() + " (tenant_id, cid, cname) VALUES (850305, 'C1', 'Pumps')");
        fireRun(hook, f.tenant());

        assertThat(markedKeys(adminDs, f.tenant(), f.index(), REASON)).containsExactly(E1.toString());
    }

    // The crosswalk is matched PER SOURCE: source id 'X' exists in both sources, under two different entities. Only
    // s1's X holds C1, so only E1 may be marked; without `ei.source = ?` s1's target would also reach s2's X -> E2.
    @Test
    void the_crosswalk_is_matched_per_source_when_two_sources_share_a_source_id() throws Exception {
        TwoSourceFx f = twoSourceFixture(850_306);
        row(f, f.s1(), "X", "C1");
        row(f, f.s2(), "X", "C9");
        insertIdentity(adminDs, f.tenant(), f.entity(), f.s1(), "X", E1);
        insertIdentity(adminDs, f.tenant(), f.entity(), f.s2(), "X", E2);
        settle(f);

        exec(adminDs, "INSERT INTO connector." + f.lookup() + " (tenant_id, cid, cname) VALUES (850306, 'C1', 'Pumps')");
        fireRun(hook, f.tenant());

        assertThat(markedKeys(adminDs, f.tenant(), f.index(), REASON)).containsExactly(E1.toString());
    }

    @Test
    void a_key_held_only_by_undecided_rows_marks_nothing_but_the_scan_ran() throws Exception {
        Fx f = fixture(850_304);
        exec(adminDs, "INSERT INTO connector." + f.lookup() + " (tenant_id, cid, cname) VALUES (850304, 'C3', 'Gaskets')");
        fireRun(hook, f.tenant());
        assertThat(markedKeys(adminDs, f.tenant(), f.index(), REASON))
            .as("P6 has no identity: not a document, its decision will mark it").isEmpty();
        assertThat(watermarkRowCount(adminDs, f.tenant(), "connector." + f.lookup())).isEqualTo(1L);
    }

    @Test
    void the_crosswalk_and_lookup_of_another_tenant_never_mark_this_tenants_entities() throws Exception {
        long t1 = 850_311, t2 = 850_312;
        String n = String.valueOf(System.nanoTime());
        String products = "t_rm_iso_p_" + n, lookup = "t_rm_iso_c_" + n, entity = "ResIso" + n;
        createTable(adminDs, products, "pid text, cid text, pname text");
        createTable(adminDs, lookup, "cid text, cname text");
        yaml(t1).body(resolutionJoinEntityYaml(entity, products, lookup, "category_name")).post("/api/entities/apply").then().statusCode(200);
        yaml(t2).body(resolutionJoinEntityYaml(entity, products, lookup, "category_name")).post("/api/entities/apply").then().statusCode(200);
        String i1 = unique("res-iso-1"), i2 = unique("res-iso-2");
        yaml(t1).body(searchIndex(i1, entity, "product_name")).post("/api/search-indexes/apply").then().statusCode(200);
        yaml(t2).body(searchIndex(i2, entity, "product_name")).post("/api/search-indexes/apply").then().statusCode(200);
        // the same physical tables, one product per tenant on the SAME category key C1 and the SAME source id
        exec(adminDs, "INSERT INTO connector." + products + " (tenant_id, pid, cid, pname) VALUES (850311, 'P1', 'C1', 'n')");
        exec(adminDs, "INSERT INTO connector." + products + " (tenant_id, pid, cid, pname) VALUES (850312, 'P1', 'C1', 'n')");
        insertIdentity(adminDs, t1, entity, products, "P1", E1);
        insertIdentity(adminDs, t2, entity, products, "P1", E2);
        fireRun(hook, t1);
        fireRun(hook, t2);
        exec(adminDs, "UPDATE connector." + products + " SET _ingested_at = now() - interval '1 hour'");
        exec(adminDs, "DELETE FROM core.search_projection_mark WHERE tenant_id IN (850311, 850312)");

        // only tenant 2 gets a lookup row for C1; tenant 1 gets one for a key nobody holds, so its scan runs
        exec(adminDs, "INSERT INTO connector." + lookup + " (tenant_id, cid, cname) VALUES (850312, 'C1', 'Other')");
        exec(adminDs, "INSERT INTO connector." + lookup + " (tenant_id, cid, cname) VALUES (850311, 'C5', 'Else')");

        fireRun(hook, t1);
        assertThat(markedKeys(adminDs, t1, i1, REASON)).isEmpty();
        assertThat(watermarkRowCount(adminDs, t1, "connector." + lookup)).as("tenant 1's scan did run").isEqualTo(1L);
        fireRun(hook, t2);
        assertThat(markedKeys(adminDs, t2, i2, REASON)).containsExactly(E2.toString());
        assertThat(markedKeys(adminDs, t1, i1, REASON)).isEmpty();
    }

    @Test
    void a_key_held_by_more_entities_than_one_page_is_fully_marked() throws Exception {
        long tenant = 850_321;
        String n = String.valueOf(System.nanoTime());
        String products = "t_rm_pg_p_" + n, lookup = "t_rm_pg_c_" + n, entity = "ResPaging" + n;
        createTable(adminDs, products, "pid text, cid text, pname text");
        createTable(adminDs, lookup, "cid text, cname text");
        yaml(tenant).body(resolutionJoinEntityYaml(entity, products, lookup, "category_name")).post("/api/entities/apply").then().statusCode(200);
        String index = unique("res-paging");
        yaml(tenant).body(searchIndex(index, entity, "product_name")).post("/api/search-indexes/apply").then().statusCode(200);
        UUID[] entities = new UUID[5];
        for (int i = 0; i < 5; i++) {
            entities[i] = UUID.fromString("00000000-0000-0000-0000-00000000b00" + i);
            exec(adminDs, "INSERT INTO connector." + products + " (tenant_id, pid, cid, pname) VALUES ("
                + tenant + ", 'P" + i + "', 'C1', 'n')");
            insertIdentity(adminDs, tenant, entity, products, "P" + i, entities[i]);
        }
        exec(adminDs, "INSERT INTO connector." + lookup + " (tenant_id, cid, cname) VALUES (850321, 'C1', 'Pumps')");

        SearchProjectionRunHook paged = new SearchProjectionRunHook();
        paged.ds = adminDs;
        paged.registry = registry;
        paged.markStore = markStore;
        paged.watermarkStore = watermarkStore;
        paged.matchKeysBatchSize = 2;                                      // 5 entities -> 3 slices
        paged.processTable(tenant, lookup, paged.targetsByTable(tenant).get(lookup));

        assertThat(markedKeys(adminDs, tenant, index, REASON)).containsExactlyElementsOf(ids(entities));
    }
}
