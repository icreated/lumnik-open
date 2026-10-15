package io.lumnik.hub.search.mark;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.entity.EntityRegistry;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.fireRun;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.joinEntityYaml;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.createTable;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.exec;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.markedKeys;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.searchIndex;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.unique;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.watermark;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.watermarkRowCount;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.yaml;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

/**
 * #847: one physical table can be a source of one entity and the lookup of another, and one lookup can serve
 * several entities. Every mark, direct and reverse, is written in the one transaction that advances the table's
 * single watermark — they commit or roll back together.
 */
@QuarkusTest
class SearchProjectionLookupSharedTableIT {

    @Inject SearchProjectionRunHook hook;
    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject EntityRegistry registry;
    @Inject SearchProjectionMarkStore markStore;

    private static String product(String name, String table, String lookup) {
        return joinEntityYaml(name, table, lookup, "category_label");
    }

    private String index(long tenant, String entity) {
        String index = unique("lk-shared");
        yaml(tenant).body(searchIndex(index, entity, "product_name")).post("/api/search-indexes/apply")
            .then().statusCode(200);
        return index;
    }

    /** One settled run, then the product rows are old news and the marks drained: only the lookup may mark from here. */
    private void settle(long tenant, String... productTables) throws Exception {
        fireRun(hook, tenant);
        for (String t : productTables) exec(adminDs, "UPDATE connector." + t + " SET _ingested_at = now() - interval '1 hour'");
        exec(adminDs, "DELETE FROM core.search_projection_mark WHERE tenant_id = " + tenant);
    }

    private static String key(String pid) {
        return MatchKeyEncoding.encode(List.of(pid));
    }

    @Test
    void a_table_that_is_a_source_of_one_entity_and_the_lookup_of_another_is_scanned_once() throws Exception {
        long tenant = 847_301;
        String n = String.valueOf(System.nanoTime());
        String cats = "t_sh_c_" + n, prods = "t_sh_p_" + n;
        createTable(adminDs, cats, "cid text, cname text");
        createTable(adminDs, prods, "pid text, cid text, pname text");
        String a = "ShareCat" + n, b = "ShareProd" + n;
        yaml(tenant).body("kind: Entity\nname: " + a + "\nmatch: [category_code]\nscopes: [lk]\nsources:\n"
            + "  - table: " + cats + "\n    map: { cid: category_code, cname: category_name }\n")
            .post("/api/entities/apply").then().statusCode(200);
        yaml(tenant).body(product(b, prods, cats)).post("/api/entities/apply").then().statusCode(200);
        String ia = unique("lk-shared-a");
        yaml(tenant).body(searchIndex(ia, a, "category_name")).post("/api/search-indexes/apply").then().statusCode(200);
        String ib = index(tenant, b);

        exec(adminDs, "INSERT INTO connector." + prods + " (tenant_id, pid, cid, pname) VALUES (847301, 'P1', 'C1', 'n')");
        settle(tenant, prods);
        exec(adminDs, "INSERT INTO connector." + cats + " (tenant_id, cid, cname) VALUES (847301, 'C1', 'Pumps')");

        fireRun(hook, tenant);

        assertThat(markedKeys(adminDs, tenant, ia, "ingestion-run")).containsExactly(key("C1"));
        assertThat(markedKeys(adminDs, tenant, ib, "lookup-change")).containsExactly(key("P1"));
        assertThat(watermarkRowCount(adminDs, tenant, "connector." + cats)).isEqualTo(1L);
    }

    @Test
    void one_lookup_serving_two_entities_marks_both_from_the_same_scan() throws Exception {
        long tenant = 847_302;
        String n = String.valueOf(System.nanoTime());
        String cats = "t_sh2_c_" + n, p1 = "t_sh2_p1_" + n, p2 = "t_sh2_p2_" + n;
        createTable(adminDs, cats, "cid text, cname text");
        createTable(adminDs, p1, "pid text, cid text, pname text");
        createTable(adminDs, p2, "pid text, cid text, pname text");
        String e1 = "ShareOne" + n, e2 = "ShareTwo" + n;
        yaml(tenant).body(product(e1, p1, cats)).post("/api/entities/apply").then().statusCode(200);
        yaml(tenant).body(product(e2, p2, cats)).post("/api/entities/apply").then().statusCode(200);
        String i1 = index(tenant, e1), i2 = index(tenant, e2);
        exec(adminDs, "INSERT INTO connector." + p1 + " (tenant_id, pid, cid, pname) VALUES (847302, 'A1', 'C1', 'n')");
        exec(adminDs, "INSERT INTO connector." + p2 + " (tenant_id, pid, cid, pname) VALUES (847302, 'B1', 'C1', 'n')");
        settle(tenant, p1, p2);
        exec(adminDs, "INSERT INTO connector." + cats + " (tenant_id, cid, cname) VALUES (847302, 'C1', 'Pumps')");

        fireRun(hook, tenant);

        assertThat(markedKeys(adminDs, tenant, i1, "lookup-change")).containsExactly(key("A1"));
        assertThat(markedKeys(adminDs, tenant, i2, "lookup-change")).containsExactly(key("B1"));
        assertThat(watermarkRowCount(adminDs, tenant, "connector." + cats)).isEqualTo(1L);
    }

    @Test
    void a_failure_before_the_watermark_advance_rolls_back_the_marks_then_a_retry_recreates_both() throws Exception {
        long tenant = 847_303;
        String n = String.valueOf(System.nanoTime());
        String cats = "t_sh3_c_" + n, prods = "t_sh3_p_" + n;
        createTable(adminDs, cats, "cid text, cname text");
        createTable(adminDs, prods, "pid text, cid text, pname text");
        String e = "ShareRollback" + n;
        yaml(tenant).body(product(e, prods, cats)).post("/api/entities/apply").then().statusCode(200);
        String idx = index(tenant, e);
        exec(adminDs, "INSERT INTO connector." + prods + " (tenant_id, pid, cid, pname) VALUES (847303, 'P1', 'C1', 'n')");
        exec(adminDs, "INSERT INTO connector." + cats + " (tenant_id, cid, cname) VALUES (847303, 'C1', 'Pumps')");

        SearchProjectionWatermarkStore failing = spy(new SearchProjectionWatermarkStore());
        failing.ds = adminDs;
        doThrow(new SQLException("boom")).when(failing).advance(any(), anyLong(), anyString(), any());
        SearchProjectionRunHook direct = new SearchProjectionRunHook();
        direct.ds = adminDs;
        direct.registry = registry;
        direct.markStore = markStore;
        direct.watermarkStore = failing;

        assertThatThrownBy(() -> direct.processTable(tenant, cats, direct.targetsByTable(tenant).get(cats)))
            .isInstanceOf(SQLException.class);

        assertThat(markedKeys(adminDs, tenant, idx, "lookup-change"))
            .as("the whole transaction rolled back: no mark survives").isEmpty();
        assertThat(watermark(adminDs, tenant, "connector." + cats))
            .as("and the watermark did not move").isNull();

        doCallRealMethod().when(failing).advance(any(), anyLong(), anyString(), any());
        direct.processTable(tenant, cats, direct.targetsByTable(tenant).get(cats));

        assertThat(markedKeys(adminDs, tenant, idx, "lookup-change")).containsExactly(key("P1"));
        assertThat(watermark(adminDs, tenant, "connector." + cats)).isNotNull();
    }
}
