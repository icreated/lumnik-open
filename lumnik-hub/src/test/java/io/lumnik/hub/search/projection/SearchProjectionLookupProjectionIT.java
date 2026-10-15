package io.lumnik.hub.search.projection;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.search.ProjectionDefinition;
import io.lumnik.extraction.spi.search.ProjectionDocument;
import io.lumnik.extraction.spi.search.ProjectionSink;
import io.lumnik.extraction.spi.search.ProviderStatus;
import io.lumnik.hub.entity.EntityRegistry;
import io.lumnik.hub.search.mark.SearchProjectionMarkStore;
import io.lumnik.hub.search.mark.SearchProjectionReconciler;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.exec;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.createTable;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.joinEntityYaml;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.searchIndexYaml;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.unique;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.workerWith;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.yaml;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * #847, end to end through the projection: a lookup row inserted after a product was projected re-projects that
 * product's document with the joined value, and a second row of different content for the same key (the key is
 * now ambiguous, slice 1's contract) re-projects it with NULL — never with either name.
 */
@QuarkusTest
class SearchProjectionLookupProjectionIT {

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject @DataSource("readonly") AgroalDataSource readonlyDs;
    @Inject EntityRegistry registry;
    @Inject SearchProjectionMarkStore markStore;
    @Inject SearchProjectionStateStore stateStore;
    @Inject SearchProjectionReconciler reconciler;

    private static long count(List<ProjectionDocument> docs, String pid) {
        return docs.stream().filter(d -> d.fields().get("product_name").equals("n" + pid)).count();
    }

    private static Object categoryOf(List<ProjectionDocument> docs, String pid) {
        return docs.stream().filter(d -> d.fields().get("product_name").equals("n" + pid))
            .reduce((first, second) -> second).orElseThrow().fields().get("category_name");
    }

    @Test
    void a_lookup_row_re_projects_the_products_holding_its_key_and_ambiguity_blanks_the_value() throws Exception {
        long tenant = 847_601L;
        String n = String.valueOf(System.nanoTime());
        String products = "t_lp_p_" + n, lookup = "t_lp_c_" + n;
        createTable(adminDs, products, "pid text, cid text, pname text");
        createTable(adminDs, lookup, "cid text, cname text");
        String entity = "LookupProjection" + n;
        yaml(tenant).body(joinEntityYaml(entity, products, lookup, "category_name"))
            .post("/api/entities/apply").then().statusCode(200);
        for (String[] r : new String[][] {{"P1", "C1"}, {"P2", "C1"}, {"P3", "C2"}}) {
            exec(adminDs, "INSERT INTO connector." + products + " (tenant_id, pid, cid, pname) VALUES ("
                + tenant + ", '" + r[0] + "', '" + r[1] + "', 'n" + r[0] + "')");
        }
        String idx = unique("lookup-projection-");
        // a joined column is a valid SearchIndex field now (#847): this apply used to be refused
        yaml(tenant).body(searchIndexYaml(idx, entity, "product_name", "category_name"))
            .post("/api/search-indexes/apply").then().statusCode(200).body("status", equalTo("valid"));

        RecordingSink rebuild = new RecordingSink();
        workerWith(rebuild, markStore, stateStore, adminDs, readonlyDs, registry).rebuild(tenant, idx);
        assertThat(rebuild.upserted).hasSize(3);
        assertThat(rebuild.upserted).allSatisfy(d -> assertThat(d.fields().get("category_name")).isNull());

        reconciler.sweep();                                         // settles every watermark, marks the products once
        exec(adminDs, "UPDATE connector." + products + " SET _ingested_at = now() - interval '1 hour'");
        markStore.deleteAllFor(tenant, idx);                        // only the lookup may mark from here

        // 1. the first lookup row for C1
        exec(adminDs, "INSERT INTO connector." + lookup + " (tenant_id, cid, cname) VALUES (" + tenant + ", 'C1', 'Pumps')");
        reconciler.sweep();
        RecordingSink first = new RecordingSink();
        workerWith(first, markStore, stateStore, adminDs, readonlyDs, registry).catchUp(tenant, idx);
        assertThat(count(first.upserted, "P1")).as("P1 re-projected").isEqualTo(1);
        assertThat(count(first.upserted, "P2")).as("P2 re-projected").isEqualTo(1);
        assertThat(count(first.upserted, "P3")).as("P3 holds another key: untouched").isZero();
        assertThat(categoryOf(first.upserted, "P1")).isEqualTo("Pumps");
        assertThat(categoryOf(first.upserted, "P2")).isEqualTo("Pumps");

        // 2. a second row of different content for C1: the key is ambiguous, the value is NULL, not a chosen name
        exec(adminDs, "INSERT INTO connector." + lookup + " (tenant_id, cid, cname) VALUES (" + tenant + ", 'C1', 'Pompes')");
        reconciler.sweep();
        RecordingSink second = new RecordingSink();
        workerWith(second, markStore, stateStore, adminDs, readonlyDs, registry).catchUp(tenant, idx);
        assertThat(count(second.upserted, "P1")).isEqualTo(1);
        assertThat(categoryOf(second.upserted, "P1")).as("ambiguous key: NULL, never Pumps or Pompes").isNull();
        assertThat(categoryOf(second.upserted, "P2")).isNull();
        assertThat(count(second.upserted, "P3")).isZero();
    }

    private static final class RecordingSink implements ProjectionSink {
        final List<ProjectionDocument> upserted = new CopyOnWriteArrayList<>();
        private int generationCounter = 0;

        @Override public String name() { return "fake"; }
        @Override public ProviderStatus status() { return null; }
        @Override public synchronized String beginGeneration(long t, String i, ProjectionDefinition d) { return "gen-" + (++generationCounter); }
        @Override public void upsert(long t, String i, String g, List<ProjectionDocument> docs) { upserted.addAll(docs); }
        @Override public void delete(long t, String i, String g, List<String> ids) {}
        @Override public void publish(long t, String i, String g) {}
        @Override public void abandon(long t, String i, String g) {}
        @Override public void purgeIndex(long t, String i) {}
        @Override public long liveDocumentCount(long t, String i) { return 0L; }
    }
}
