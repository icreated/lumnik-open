package io.lumnik.hub.search.mark;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.entity.EntityRegistry;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.advanceFailsOnce;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.createTable;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.exec;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.fireRun;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.insertIdentity;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.joinEntityYaml;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.markedKeys;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.resolutionJoinEntityYaml;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.searchIndex;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.unique;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.watermark;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.watermarkRowCount;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.yaml;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * #850: one lookup table serving a match: entity AND a resolution: entity. One scan, every family of marks written,
 * then the single watermark advances — all in one transaction.
 */
@QuarkusTest
class SearchProjectionResolvedLookupSharedTableIT {

    @Inject SearchProjectionRunHook hook;
    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject EntityRegistry registry;
    @Inject SearchProjectionMarkStore markStore;

    static final UUID ER = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    record Fx(long tenant, String lookup, String matchIndex, String resIndex) {}

    /** Match entity over mp (product P1 → C1) and resolution entity over rp (P1 → C1, decided ER), ONE lookup. */
    private Fx fixture(long tenant) throws Exception {
        String n = String.valueOf(System.nanoTime());
        String lookup = "t_rs_c_" + n, mp = "t_rs_mp_" + n, rp = "t_rs_rp_" + n;
        createTable(adminDs, lookup, "cid text, cname text");
        createTable(adminDs, mp, "pid text, cid text, pname text");
        createTable(adminDs, rp, "pid text, cid text, pname text");
        String me = "ResShareMatch" + n, re = "ResShareRes" + n;
        yaml(tenant).body(joinEntityYaml(me, mp, lookup, "category_label")).post("/api/entities/apply").then().statusCode(200);
        yaml(tenant).body(resolutionJoinEntityYaml(re, rp, lookup, "category_label")).post("/api/entities/apply").then().statusCode(200);
        String mi = unique("rs-m"), ri = unique("rs-r");
        yaml(tenant).body(searchIndex(mi, me, "product_name")).post("/api/search-indexes/apply").then().statusCode(200);
        yaml(tenant).body(searchIndex(ri, re, "product_name")).post("/api/search-indexes/apply").then().statusCode(200);
        exec(adminDs, "INSERT INTO connector." + mp + " (tenant_id, pid, cid, pname) VALUES (" + tenant + ", 'P1', 'C1', 'n')");
        exec(adminDs, "INSERT INTO connector." + rp + " (tenant_id, pid, cid, pname) VALUES (" + tenant + ", 'P1', 'C1', 'n')");
        insertIdentity(adminDs, tenant, re, rp, "P1", ER);
        fireRun(hook, tenant);
        exec(adminDs, "UPDATE connector." + mp + " SET _ingested_at = now() - interval '1 hour'");
        exec(adminDs, "UPDATE connector." + rp + " SET _ingested_at = now() - interval '1 hour'");
        exec(adminDs, "DELETE FROM core.search_projection_mark WHERE tenant_id = " + tenant);
        return new Fx(tenant, lookup, mi, ri);
    }

    @Test
    void a_lookup_shared_by_a_match_and_a_resolution_entity_marks_both_from_the_same_scan() throws Exception {
        Fx f = fixture(850_401);
        exec(adminDs, "INSERT INTO connector." + f.lookup() + " (tenant_id, cid, cname) VALUES (850401, 'C1', 'Pumps')");

        fireRun(hook, f.tenant());

        assertThat(markedKeys(adminDs, f.tenant(), f.matchIndex(), "lookup-change"))
            .containsExactly(MatchKeyEncoding.encode(List.of("P1")));
        assertThat(markedKeys(adminDs, f.tenant(), f.resIndex(), "lookup-change")).containsExactly(ER.toString());
        assertThat(watermarkRowCount(adminDs, f.tenant(), "connector." + f.lookup())).isEqualTo(1L);
    }

    static Stream<Arguments> failuresBeforeTheAdvance() {
        return Stream.of(
            Arguments.of(850_402L, new RuntimeException("boom", new SQLException("boom"))),
            // #1649: an Error skipped the rollback, and the finally that restored autocommit committed the marks. Not an
            // OutOfMemoryError: one escaping by mistake would stop the whole JUnit run.
            Arguments.of(850_403L, new StackOverflowError("injected")));
    }

    @ParameterizedTest
    @MethodSource("failuresBeforeTheAdvance")
    void a_failure_before_the_advance_rolls_back_both_families_and_a_retry_recreates_them(long tenant, Throwable failure)
            throws Exception {
        Fx f = fixture(tenant);
        exec(adminDs, "INSERT INTO connector." + f.lookup() + " (tenant_id, cid, cname) VALUES (" + tenant + ", 'C1', 'Pumps')");
        var before = watermark(adminDs, f.tenant(), "connector." + f.lookup());
        SearchProjectionRunHook direct = advanceFailsOnce(adminDs, registry, markStore, failure);

        Throwable thrown = catchThrowable(
            () -> direct.processTable(f.tenant(), f.lookup(), direct.targetsByTable(f.tenant()).get(f.lookup())));
        // An Error leaves as itself; a database failure comes back labelled by Jdbc.transaction, as its cause.
        assertThat(failure instanceof Error ? thrown : thrown.getCause()).isSameAs(failure);
        assertThat(markedKeys(adminDs, f.tenant(), f.matchIndex(), "lookup-change")).isEmpty();
        assertThat(markedKeys(adminDs, f.tenant(), f.resIndex(), "lookup-change")).isEmpty();
        assertThat(watermark(adminDs, f.tenant(), "connector." + f.lookup())).isEqualTo(before);

        direct.processTable(f.tenant(), f.lookup(), direct.targetsByTable(f.tenant()).get(f.lookup()));

        assertThat(markedKeys(adminDs, f.tenant(), f.matchIndex(), "lookup-change")).hasSize(1);
        assertThat(markedKeys(adminDs, f.tenant(), f.resIndex(), "lookup-change")).containsExactly(ER.toString());
    }
}
