package io.lumnik.hub.search.mark;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.applyResolutionEntity;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.createTable;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.resolutionJoinEntityYaml;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.searchIndex;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.unique;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.yaml;
import static org.assertj.core.api.Assertions.assertThat;

/** #850: what the producer discovers for a resolution entity with joins — and what it still does not scan. */
@QuarkusTest
class SearchProjectionResolvedLookupTargetsIT {

    @Inject SearchProjectionRunHook hook;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    @Test
    void a_resolution_entity_yields_one_resolved_target_on_its_lookup_and_nothing_on_its_sources() throws Exception {
        long tenant = 850_201;
        String n = String.valueOf(System.nanoTime());
        String products = "t_rt_p_" + n, lookup = "t_rt_c_" + n;
        createTable(adminDs, products, "pid text, cid text, pname text");
        createTable(adminDs, lookup, "cid text, cname text");
        String entity = "ResTargets" + n;
        yaml(tenant).body(resolutionJoinEntityYaml(entity, products, lookup, "category_name"))
            .post("/api/entities/apply").then().statusCode(200);
        String index = unique("rt");
        yaml(tenant).body(searchIndex(index, entity, "product_name")).post("/api/search-indexes/apply")
            .then().statusCode(200);

        var targets = hook.targetsByTable(tenant);

        assertThat(targets.get(products)).as("a resolution entity's sources are bounded by run coverage, not scanned").isNull();
        assertThat(targets.get(lookup)).hasSize(1);
        assertThat(targets.get(lookup).get(0)).isInstanceOfSatisfying(SearchProjectionRunHook.ResolvedLookupTarget.class, t -> {
            assertThat(t.entity()).isEqualTo(entity);
            assertThat(t.entityType()).isEqualTo(entity.toLowerCase(Locale.ROOT));
            assertThat(t.indexNames()).containsExactly(index);
            assertThat(t.sourceTable()).isEqualTo(products);
            assertThat(t.physicalFromCol()).isEqualTo("cid");
            assertThat(t.physicalIdentityCol()).isEqualTo("pid");
            assertThat(t.lookupToCol()).isEqualTo("cid");
            assertThat(t.reason()).isEqualTo("lookup-change");
        });
        assertThat(hook.resolutionLookupTables(entity)).containsExactly("connector." + lookup);
        assertThat(hook.matchSourceTables(entity)).as("the advancer's match branch must stay closed").isEmpty();
    }

    @Test
    void without_a_valid_search_index_nothing_is_discovered_but_the_bound_still_knows_the_lookup() throws Exception {
        long tenant = 850_202;
        String n = String.valueOf(System.nanoTime());
        String products = "t_rt2_p_" + n, lookup = "t_rt2_c_" + n;
        createTable(adminDs, products, "pid text, cid text, pname text");
        createTable(adminDs, lookup, "cid text, cname text");
        String entity = "ResTargetsNoIdx" + n;
        yaml(tenant).body(resolutionJoinEntityYaml(entity, products, lookup, "category_name"))
            .post("/api/entities/apply").then().statusCode(200);

        assertThat(hook.targetsByTable(tenant).get(lookup)).isNull();
        assertThat(hook.resolutionLookupTables(entity)).containsExactly("connector." + lookup);
    }

    @Test
    void a_resolution_entity_without_joins_discovers_nothing_as_before() throws Exception {
        long tenant = 850_203;
        String n = String.valueOf(System.nanoTime());
        String table = "t_rt3_" + n;
        applyResolutionEntity(adminDs, tenant, "ResPlain" + n, table);
        String index = unique("rt3");
        yaml(tenant).body(searchIndex(index, "ResPlain" + n, "name")).post("/api/search-indexes/apply");

        assertThat(hook.targetsByTable(tenant)).doesNotContainKey(table);
        assertThat(hook.resolutionLookupTables("ResPlain" + n)).isEmpty();
    }
}
