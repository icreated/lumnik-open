package io.lumnik.hub.search.mark;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.search.mark.SearchProjectionRunHook.LookupTarget;
import io.lumnik.hub.search.mark.SearchProjectionRunHook.SourceTarget;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.createTable;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.searchIndex;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.unique;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.yaml;
import static org.assertj.core.api.Assertions.assertThat;

/** #847: the lookup of a join is a scanned table, with a reverse mapping through every source that maps {@code from}. */
@QuarkusTest
class SearchProjectionLookupTargetsIT {

    @Inject SearchProjectionRunHook hook;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    @Test
    void a_lookup_table_gets_one_reverse_target_per_source_that_maps_from() throws Exception {
        long tenant = 847_101;
        String n = String.valueOf(System.nanoTime());
        String p1 = "t_lk_p1_" + n, p2 = "t_lk_p2_" + n, p3 = "t_lk_p3_" + n, cat = "t_lk_c_" + n;
        createTable(adminDs, p1, "pid text, cid text, pname text");
        createTable(adminDs, p2, "pid text, cid text, pname text");
        createTable(adminDs, p3, "pid text, pname text");                 // maps no category column
        createTable(adminDs, cat, "cid text, cname text");
        String entity = "LkTargets" + n;
        yaml(tenant).body("kind: Entity\nname: " + entity + "\nmatch: [product_id]\nscopes: [lk]\nsources:\n"
            + "  - table: " + p1 + "\n    map: { pid: product_id, cid: category_id, pname: product_name }\n"
            + "  - table: " + p2 + "\n    map: { pid: product_id, cid: category_id, pname: product_name }\n"
            + "  - table: " + p3 + "\n    map: { pid: product_id, pname: product_name }\n"
            + "joins:\n  - table: " + cat + "\n    from: category_id\n    to: cid\n    map: { cname: category_name }\n")
            .post("/api/entities/apply").then().statusCode(200);
        String index = unique("lk-targets");
        yaml(tenant).body(searchIndex(index, entity, "product_name")).post("/api/search-indexes/apply")
            .then().statusCode(200);

        var byTable = hook.targetsByTable(tenant);

        assertThat(byTable.get(p1)).hasOnlyElementsOfType(SourceTarget.class).hasSize(1);
        assertThat(byTable.get(p3)).hasOnlyElementsOfType(SourceTarget.class).hasSize(1);
        List<LookupTarget> lookups = byTable.get(cat).stream().map(LookupTarget.class::cast).toList();
        assertThat(lookups).extracting(LookupTarget::sourceTable).containsExactlyInAnyOrder(p1, p2);
        assertThat(lookups).allSatisfy(t -> {
            assertThat(t.physicalFromCol()).isEqualTo("cid");
            assertThat(t.lookupToCol()).isEqualTo("cid");
            assertThat(t.physicalMatchCols()).containsExactly("pid");
            assertThat(t.indexNames()).containsExactly(index);
        });
        assertThat(hook.matchSourceTables(entity))
            .containsExactlyInAnyOrder("connector." + p1, "connector." + p2, "connector." + p3, "connector." + cat);
    }

    // Copilot on #851: the same physical table listed twice in sources[], each with its own map — the reverse target of
    // each source must name THAT source's own physical column for joins[].from, not the first one found for the table.
    @Test
    void a_table_listed_twice_with_different_maps_resolves_from_per_source() throws Exception {
        long tenant = 847_102;
        String n = String.valueOf(System.nanoTime());
        String twice = "t_lk_twice_" + n, cat = "t_lk_c2_" + n;
        createTable(adminDs, twice, "pid text, cid text, cid2 text, pname text");
        createTable(adminDs, cat, "cid text, cname text");
        String entity = "LkTwice" + n;
        yaml(tenant).body("kind: Entity\nname: " + entity + "\nmatch: [product_id]\nscopes: [lk]\nsources:\n"
            + "  - table: " + twice + "\n    map: { pid: product_id, cid: category_id, pname: product_name }\n"
            + "  - table: " + twice + "\n    map: { pid: product_id, cid2: category_id, pname: product_name }\n"
            + "joins:\n  - table: " + cat + "\n    from: category_id\n    to: cid\n    map: { cname: category_name }\n")
            .post("/api/entities/apply").then().statusCode(200);
        yaml(tenant).body(searchIndex(unique("lk-twice"), entity, "product_name")).post("/api/search-indexes/apply")
            .then().statusCode(200);

        List<LookupTarget> lookups = hook.targetsByTable(tenant).get(cat).stream().map(LookupTarget.class::cast).toList();

        assertThat(lookups).extracting(LookupTarget::physicalFromCol).containsExactlyInAnyOrder("cid", "cid2");
    }
}
