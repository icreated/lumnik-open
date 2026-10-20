package io.lumnik.hub.search.mark;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.entity.EntityRegistry;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.fireRun;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.joinEntityYaml;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.createTable;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.exec;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.markedKeys;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.searchIndex;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.unique;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.watermarkRowCount;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.yaml;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #847: a change in the lookup table of a join marks the fused rows holding the changed key. Lookup tables
 * are append-only keyed by content hash: a changed row is a NEW row, an identical re-ingest only bumps
 * {@code _ingested_at} — the tests model exactly those two events.
 */
@QuarkusTest
class SearchProjectionLookupMarksIT {

    private static final String REASON = "lookup-change";

    @Inject SearchProjectionRunHook hook;
    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject EntityRegistry registry;
    @Inject SearchProjectionMarkStore markStore;
    @Inject SearchProjectionWatermarkStore watermarkStore;

    record Fx(long tenant, String products, String lookup, String index) {}

    /** P1,P2 -> C1 ; P3 -> C2 ; an empty lookup; a SearchIndex over the entity; one settled run. */
    private Fx fixture(long tenant) throws Exception {
        String n = String.valueOf(System.nanoTime());
        String products = "t_lm_p_" + n, lookup = "t_lm_c_" + n;
        createTable(adminDs, products, "pid text, cid text, pname text");
        createTable(adminDs, lookup, "cid text, cname text");
        String entity = "LkMarks" + n;
        yaml(tenant).body(joinEntityYaml(entity, products, lookup, "category_name"))
            .post("/api/entities/apply").then().statusCode(200);
        String index = unique("lk-marks");
        yaml(tenant).body(searchIndex(index, entity, "product_name")).post("/api/search-indexes/apply")
            .then().statusCode(200);
        for (String[] r : new String[][] {{"P1", "C1"}, {"P2", "C1"}, {"P3", "C2"}}) {
            exec(adminDs, "INSERT INTO connector." + products + " (tenant_id, pid, cid, pname) VALUES ("
                + tenant + ", '" + r[0] + "', '" + r[1] + "', 'n" + r[0] + "')");
        }
        fireRun(hook, tenant);                                             // settles the direct marks and the watermarks
        // The products were ingested long ago: only the lookup may produce marks from here on. (Marks are keyed by
        // (index, key) and a re-mark relabels them (#852): a direct re-scan of fresh rows in the same tick would
        // make the label depend on which table the tick scanned last.)
        exec(adminDs, "UPDATE connector." + products + " SET _ingested_at = now() - interval '1 hour'");
        exec(adminDs, "DELETE FROM core.search_projection_mark WHERE tenant_id = " + tenant);
        return new Fx(tenant, products, lookup, index);
    }

    private static List<String> keys(String... pids) {
        return Arrays.stream(pids).map(p -> MatchKeyEncoding.encode(List.of(p))).sorted().toList();
    }

    /**
     * #1329: a calendar keyed by day, joined from a key one source holds as a time and another as a day. The reverse
     * scan of each source must compare as the view does — at the UTC day — or the time source's record is never marked
     * when its day's calendar row changes (its text {@code 2026-07-01 14:30:00+00} never equals {@code 2026-07-01}).
     */
    @Test
    void a_day_keyed_lookup_marks_the_records_of_that_day_whichever_source_holds_it() throws Exception {
        long tenant = 1_329_001L;
        String n = String.valueOf(System.nanoTime());
        String timed = "t_lm_dt_" + n, days = "t_lm_dd_" + n, cal = "t_lm_cal_" + n;
        createTable(adminDs, timed, "pid text, due timestamptz");
        createTable(adminDs, days, "pid text, day date");
        createTable(adminDs, cal, "day date, label text");
        String entity = "LkDay" + n;
        yaml(tenant).body("kind: Entity\nname: " + entity + "\nmatch: [pid]\nscopes: [lk]\nsources:\n"
                + "  - table: " + timed + "\n    map: { pid: pid, due: due }\n"
                + "  - table: " + days + "\n    map: { pid: pid, day: due }\n"
                + "joins:\n  - table: " + cal + "\n    from: due\n    to: day\n    map: { label: day_label }\n")
            .post("/api/entities/apply").then().statusCode(200);
        String index = unique("lk-day");
        yaml(tenant).body(searchIndex(index, entity, "pid")).post("/api/search-indexes/apply").then().statusCode(200);
        exec(adminDs, "INSERT INTO connector." + timed + " (tenant_id, pid, due) VALUES (" + tenant
            + ", 'P1', '2026-07-01 14:30+00'), (" + tenant + ", 'P2', '2026-07-02 09:00+00')");
        exec(adminDs, "INSERT INTO connector." + days + " (tenant_id, pid, day) VALUES (" + tenant + ", 'P3', '2026-07-01')");
        fireRun(hook, tenant);
        exec(adminDs, "UPDATE connector." + timed + " SET _ingested_at = now() - interval '1 hour'");
        exec(adminDs, "UPDATE connector." + days + " SET _ingested_at = now() - interval '1 hour'");
        exec(adminDs, "DELETE FROM core.search_projection_mark WHERE tenant_id = " + tenant);

        exec(adminDs, "INSERT INTO connector." + cal + " (tenant_id, day, label) VALUES (" + tenant + ", '2026-07-01', 'Day A')");
        fireRun(hook, tenant);

        assertThat(markedKeys(adminDs, tenant, index, REASON)).containsExactlyElementsOf(keys("P1", "P3"));
    }

    @Test
    void the_first_lookup_row_for_an_absent_key_marks_the_products_holding_it() throws Exception {
        Fx f = fixture(847_111);
        exec(adminDs, "INSERT INTO connector." + f.lookup() + " (tenant_id, cid, cname) VALUES (847111, 'C1', 'Pumps')");
        fireRun(hook, f.tenant());
        assertThat(markedKeys(adminDs, f.tenant(), f.index(), REASON)).containsExactlyElementsOf(keys("P1", "P2"));
    }

    @Test
    void an_additional_row_of_different_content_for_an_existing_key_marks_again() throws Exception {
        Fx f = fixture(847_112);
        exec(adminDs, "INSERT INTO connector." + f.lookup() + " (tenant_id, cid, cname) VALUES (847112, 'C1', 'Pumps')");
        fireRun(hook, f.tenant());
        exec(adminDs, "DELETE FROM core.search_projection_mark WHERE tenant_id = 847112");   // the projection drained them
        exec(adminDs, "INSERT INTO connector." + f.lookup() + " (tenant_id, cid, cname) VALUES (847112, 'C1', 'Pompes')");
        fireRun(hook, f.tenant());
        assertThat(markedKeys(adminDs, f.tenant(), f.index(), REASON)).containsExactlyElementsOf(keys("P1", "P2"));
    }

    @Test
    void an_identical_reingest_bumps_the_timestamp_and_remarks_without_growth() throws Exception {
        Fx f = fixture(847_113);
        exec(adminDs, "INSERT INTO connector." + f.lookup() + " (tenant_id, cid, cname) VALUES (847113, 'C1', 'Pumps')");
        fireRun(hook, f.tenant());
        exec(adminDs, "UPDATE connector." + f.lookup() + " SET _ingested_at = now() WHERE tenant_id = 847113");
        fireRun(hook, f.tenant());
        assertThat(markedKeys(adminDs, f.tenant(), f.index(), REASON)).containsExactlyElementsOf(keys("P1", "P2"));
    }

    @Test
    void a_lookup_row_of_another_tenant_never_marks_this_tenants_rows() throws Exception {
        long t1 = 847_201, t2 = 847_202;
        String n = String.valueOf(System.nanoTime());
        String products = "t_lm_iso_p_" + n, lookup = "t_lm_iso_c_" + n;
        createTable(adminDs, products, "pid text, cid text, pname text");
        createTable(adminDs, lookup, "cid text, cname text");
        String entity = "LkIso" + n;
        yaml(t1).body(joinEntityYaml(entity, products, lookup, "category_name"))
            .post("/api/entities/apply").then().statusCode(200);
        String i1 = unique("lk-iso-1"), i2 = unique("lk-iso-2");
        yaml(t1).body(searchIndex(i1, entity, "product_name")).post("/api/search-indexes/apply").then().statusCode(200);
        yaml(t2).body(searchIndex(i2, entity, "product_name")).post("/api/search-indexes/apply").then().statusCode(200);
        // the same physical tables, one product per tenant on the SAME category key C1
        exec(adminDs, "INSERT INTO connector." + products + " (tenant_id, pid, cid, pname) VALUES (847201, 'P1', 'C1', 'n')");
        exec(adminDs, "INSERT INTO connector." + products + " (tenant_id, pid, cid, pname) VALUES (847202, 'P9', 'C1', 'n')");
        fireRun(hook, t1);
        fireRun(hook, t2);
        exec(adminDs, "UPDATE connector." + products + " SET _ingested_at = now() - interval '1 hour'");
        exec(adminDs, "DELETE FROM core.search_projection_mark WHERE tenant_id IN (847201, 847202)");

        // only tenant 2 has a lookup row for C1; tenant 1 has one for a key no product holds — so the reverse scan
        // does run for tenant 1 (it has a changed lookup row) and only the tenant filter keeps C1 out of it
        exec(adminDs, "INSERT INTO connector." + lookup + " (tenant_id, cid, cname) VALUES (847202, 'C1', 'Other')");
        exec(adminDs, "INSERT INTO connector." + lookup + " (tenant_id, cid, cname) VALUES (847201, 'C5', 'Else')");

        fireRun(hook, t1);
        assertThat(markedKeys(adminDs, t1, i1, REASON)).as("tenant 1's only lookup row is for a key no product holds").isEmpty();
        assertThat(watermarkRowCount(adminDs, t1, "connector." + lookup)).as("but its scan did run").isEqualTo(1L);

        fireRun(hook, t2);
        assertThat(markedKeys(adminDs, t2, i2, REASON)).containsExactly(MatchKeyEncoding.encode(List.of("P9")));
        assertThat(markedKeys(adminDs, t1, i1, REASON)).isEmpty();
    }

    // #864: the join compares 1000021 and 1000021.0000 as one key; the reverse scan must agree, or the index keeps
    // the product without its category forever while the entity read shows it.
    @Test
    void a_lookup_key_of_another_scale_still_marks_the_products_holding_it() throws Exception {
        long tenant = 847_116;
        String n = String.valueOf(System.nanoTime());
        String products = "t_lm_sc_p_" + n, lookup = "t_lm_sc_c_" + n;
        createTable(adminDs, products, "pid text, cid bigint, pname text");
        createTable(adminDs, lookup, "cid numeric(18,4), cname text");
        String entity = "LkScale" + n;
        yaml(tenant).body(joinEntityYaml(entity, products, lookup, "category_name"))
            .post("/api/entities/apply").then().statusCode(200);
        String index = unique("lk-scale");
        yaml(tenant).body(searchIndex(index, entity, "product_name")).post("/api/search-indexes/apply")
            .then().statusCode(200);
        exec(adminDs, "INSERT INTO connector." + products + " (tenant_id, pid, cid, pname) VALUES ("
            + tenant + ", 'P1', 1000021, 'nP1'), (" + tenant + ", 'P2', 1000022, 'nP2')");
        fireRun(hook, tenant);
        exec(adminDs, "UPDATE connector." + products + " SET _ingested_at = now() - interval '1 hour'");
        exec(adminDs, "DELETE FROM core.search_projection_mark WHERE tenant_id = " + tenant);

        exec(adminDs, "INSERT INTO connector." + lookup + " (tenant_id, cid, cname) VALUES (" + tenant + ", 1000021, 'Pompes')");
        fireRun(hook, tenant);

        assertThat(markedKeys(adminDs, tenant, index, REASON)).containsExactlyElementsOf(keys("P1"));
    }

    // Spec 4.1: the reverse query streams its keys in slices like the match keys, with its OWN bind layout (two tenant
    // params before the watermark) — a hook built directly so shrinking the slice never leaks.
    @Test
    void a_changed_key_held_by_more_rows_than_one_page_is_fully_marked() throws Exception {
        long tenant = 847_114;
        String n = String.valueOf(System.nanoTime());
        String products = "t_lm_pg_p_" + n, lookup = "t_lm_pg_c_" + n;
        createTable(adminDs, products, "pid text, cid text, pname text");
        createTable(adminDs, lookup, "cid text, cname text");
        String entity = "LkPaging" + n;
        yaml(tenant).body(joinEntityYaml(entity, products, lookup, "category_name"))
            .post("/api/entities/apply").then().statusCode(200);
        String index = unique("lk-paging");
        yaml(tenant).body(searchIndex(index, entity, "product_name")).post("/api/search-indexes/apply")
            .then().statusCode(200);
        for (String pid : new String[] {"P1", "P2", "P3", "P4", "P5"}) {
            exec(adminDs, "INSERT INTO connector." + products + " (tenant_id, pid, cid, pname) VALUES ("
                + tenant + ", '" + pid + "', 'C1', 'n')");
        }
        exec(adminDs, "INSERT INTO connector." + lookup + " (tenant_id, cid, cname) VALUES (847114, 'C1', 'Pumps')");

        SearchProjectionRunHook paged = new SearchProjectionRunHook();
        paged.ds = adminDs;
        paged.registry = registry;
        paged.markStore = markStore;
        paged.watermarkStore = watermarkStore;
        paged.matchKeysBatchSize = 2;                              // 5 keys -> 3 slices
        paged.processTable(tenant, lookup, paged.targetsByTable(tenant).get(lookup));

        assertThat(markedKeys(adminDs, tenant, index, REASON))
            .containsExactlyElementsOf(keys("P1", "P2", "P3", "P4", "P5"));
    }

    // #852: a key already pending from a direct scan, re-marked by a lookup change, carries the LATER trigger's name.
    @Test
    void a_lookup_change_relabels_a_key_already_pending_from_ingestion() throws Exception {
        long tenant = 847_115;
        String n = String.valueOf(System.nanoTime());
        String products = "t_lm_rl_p_" + n, lookup = "t_lm_rl_c_" + n;
        createTable(adminDs, products, "pid text, cid text, pname text");
        createTable(adminDs, lookup, "cid text, cname text");
        String entity = "LkRelabel" + n;
        yaml(tenant).body(joinEntityYaml(entity, products, lookup, "category_name")).post("/api/entities/apply")
            .then().statusCode(200);
        String index = unique("lk-relabel");
        yaml(tenant).body(searchIndex(index, entity, "product_name")).post("/api/search-indexes/apply")
            .then().statusCode(200);
        for (String[] r : new String[][] {{"P1", "C1"}, {"P2", "C1"}, {"P3", "C2"}}) {
            exec(adminDs, "INSERT INTO connector." + products + " (tenant_id, pid, cid, pname) VALUES ("
                + tenant + ", '" + r[0] + "', '" + r[1] + "', 'n')");
        }
        // one table at a time, so which scan wrote last is not left to the map order of a whole tick
        hook.processTable(tenant, products, hook.targetsByTable(tenant).get(products));
        assertThat(markedKeys(adminDs, tenant, index, "ingestion-run")).containsExactlyElementsOf(keys("P1", "P2", "P3"));

        exec(adminDs, "INSERT INTO connector." + lookup + " (tenant_id, cid, cname) VALUES (847115, 'C1', 'Pumps')");
        hook.processTable(tenant, lookup, hook.targetsByTable(tenant).get(lookup));

        assertThat(markedKeys(adminDs, tenant, index, "lookup-change")).containsExactlyElementsOf(keys("P1", "P2"));
        assertThat(markedKeys(adminDs, tenant, index, "ingestion-run")).containsExactlyElementsOf(keys("P3"));
    }
}
