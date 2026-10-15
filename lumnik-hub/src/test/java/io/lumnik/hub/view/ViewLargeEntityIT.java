package io.lumnik.hub.view;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.testsupport.YamlBody;
import io.quarkus.agroal.DataSource;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * The paging lane of epic #651 (#781): a 400-row entity for tenant 1 and 240 rows for tenant 2 in the
 * SAME RLS-protected table, read only through {@code POST /api/views/render}. Two pages of 200 are
 * enough to cross a page boundary; the cost of a page on a 200,000-row table was measured once, by
 * hand (docs/apps/views.md), and is not re-measured here, and neither is the 1,000-row cut of the
 * legacy shape (ViewDataReaderIT pins it at the reader).
 * What it proves: the browser never receives more than a page, a walk reaches every row exactly once
 * and in order, filters page the same way, tenants never see each other's rows, and a cursor lifted
 * from the middle of the entity is still refused when altered or reused anywhere else. The seed is {@code generate_series}.
 *
 * <p>Not covered here, on purpose: rapid navigation and stale responses (PWA logic, pager.test.mjs),
 * responsive rendering (layout.test.mjs), and the five-second timeout mapping
 * (ViewDataReaderFailureMappingTest) — a scan of this size finishes well inside the timeout, so a
 * real timeout cannot be provoked honestly from data alone.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)   // the plain profile connects as the table owner and bypasses RLS
class ViewLargeEntityIT {

    private static final int T1_ROWS = 400;
    private static final int T2_ROWS = 240;
    private static final int PAGE = 200;
    private static final int MAX_PAGES = T1_ROWS / PAGE + 5;
    /** Each code is served by this many rows, so equal codes are common and the id tie-break decides their order. */
    private static final int CODE_REPEAT = 4;
    private static final int CODE_COUNT = (T1_ROWS + CODE_REPEAT - 1) / CODE_REPEAT;

    private static final String VIEW = """
        apiVersion: apps.lumnik.io/v1
        kind: View
        metadata: { name: large-view }
        spec:
          scope: desk
          table: connector.t_view_large
          title: Large
          columns: [code, label, amount]
        """;

    @Inject SchemaCardStore store;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    private static boolean seeded;

    private void exec(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private long count(String where) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM connector.t_view_large WHERE " + where)) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @BeforeEach
    synchronized void seed() {
        if (!seeded) {
            exec("DROP TABLE IF EXISTS connector.t_view_large");
            exec("CREATE TABLE connector.t_view_large (id BIGSERIAL PRIMARY KEY, tenant_id bigint NOT NULL, "
               + "code text, label text, amount numeric)");
            exec("ALTER TABLE connector.t_view_large ENABLE ROW LEVEL SECURITY");
            exec("CREATE POLICY t_view_large_iso ON connector.t_view_large "
               + "USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint)");
            exec("GRANT SELECT ON connector.t_view_large TO lumnik_app, lumnik_readonly");
            // codes repeat CODE_REPEAT× (g % CODE_COUNT): the tie-break on id is exercised across page boundaries
            exec("INSERT INTO connector.t_view_large (tenant_id, code, label, amount) "
               + "SELECT 1, 'K-' || lpad((g % " + CODE_COUNT + ")::text, 5, '0'), CASE WHEN g % 2 = 0 THEN 'even' ELSE 'odd' END, g "
               + "FROM generate_series(1, " + T1_ROWS + ") g");
            exec("INSERT INTO connector.t_view_large (tenant_id, code, label, amount) "
               + "SELECT 2, 'Z-' || lpad(g::text, 5, '0'), 'other', g FROM generate_series(1, " + T2_ROWS + ") g");
            exec("ANALYZE connector.t_view_large");
            seeded = true;
        }
        for (long tenant : new long[] {1L, 2L}) {
            store.save(tenant, "registry", new SchemaCard("desk", List.of(
                new SchemaCard.Table("connector.t_view_large", "Large", List.of(
                    new SchemaCard.Column("code", "text", "Code"),
                    new SchemaCard.Column("label", "text", "Label"),
                    new SchemaCard.Column("amount", "numeric", "Amount"))))));
        }
    }

    private RequestSpecification render(long tenant, int pageSize, String cursor, String... filters) {
        RequestSpecification r = given().config(YamlBody.CONFIG).header("X-Tenant-Id", String.valueOf(tenant))
            .contentType("application/yaml").body(VIEW);
        if (pageSize > 0) r = r.queryParam("pageSize", pageSize);
        if (cursor != null) r = r.queryParam("cursor", cursor);
        for (String f : filters) r = r.queryParam("filter", f);
        return r;
    }

    private JsonPath page(long tenant, int pageSize, String cursor, String... filters) {
        return render(tenant, pageSize, cursor, filters).when().post("/api/views/render")
            .then().statusCode(200).extract().jsonPath();
    }

    /** Follows nextCursor to the end like a client; every row, in the order served. */
    private List<Map<String, Object>> walk(long tenant, int pageSize, String... filters) {
        List<Map<String, Object>> all = new ArrayList<>();
        String cursor = null;
        for (int i = 0; i < MAX_PAGES; i++) {
            JsonPath p = page(tenant, pageSize, cursor, filters);
            List<Map<String, Object>> rows = p.getList("rows");
            assertThat(rows.size()).as("a response never exceeds the page").isLessThanOrEqualTo(pageSize);
            all.addAll(rows);
            if (!Boolean.TRUE.equals(p.getBoolean("hasMore"))) {
                assertThat((String) p.get("nextCursor")).as("no cursor on the last page").isNull();
                return all;
            }
            cursor = p.get("nextCursor");
        }
        throw new AssertionError("the walk never ended");
    }

    private static String code(Map<String, Object> row) { return (String) row.get("code"); }

    @Test
    void opensOnABoundedPage_andSaysMoreExist_neverTheWholeEntity() {
        JsonPath first = page(1, 50, null);
        assertThat(first.getList("rows")).hasSize(50);
        assertThat(first.getBoolean("hasMore")).isTrue();
        assertThat((String) first.get("nextCursor")).isNotBlank();
    }

    @Test
    void aWalkReachesEveryRow_onceAndInOrder() {
        // no wall-clock assertion: a page that outlived the five-second statement timeout would be a
        // 400 (ViewDataReader maps SQLSTATE 57014), and page() requires 200 on every one of them
        List<Map<String, Object>> rows = walk(1, PAGE);
        assertThat(rows).hasSize(T1_ROWS);
        // code ascending; the id tie-break is invisible to the client (id is not a shown column),
        // so order is checked on what is shown and uniqueness on (code, amount) — amount is unique per row
        Set<String> seen = new HashSet<>();
        String previous = "";
        for (Map<String, Object> r : rows) {
            assertThat(code(r).compareTo(previous)).as("codes never go backwards").isGreaterThanOrEqualTo(0);
            previous = code(r);
            assertThat(seen.add(code(r) + "|" + r.get("amount"))).as("no row served twice").isTrue();
        }
    }

    @Test
    void filtersPageTheSameWay_andMatchTheDatabaseCount() {
        assertThat(walk(1, PAGE, "label=even")).hasSize((int) count("tenant_id = 1 AND label = 'even'"));
        assertThat(walk(1, PAGE, "code~K-0001", "label=odd"))
            .hasSize((int) count("tenant_id = 1 AND code ILIKE '%K-0001%' AND label = 'odd'"));
        assertThat(walk(1, PAGE, "amount>=" + (T1_ROWS / 4))).hasSize(T1_ROWS - T1_ROWS / 4 + 1);   // T1_ROWS/4..T1_ROWS inclusive, two pages
    }

    @Test
    void tenantsNeverSeeEachOthersRows_acrossPages() {
        List<Map<String, Object>> mine = walk(2, PAGE);
        assertThat(mine).hasSize(T2_ROWS).allSatisfy(r -> assertThat(code(r)).startsWith("Z-"));
        assertThat(page(1, PAGE, null, "code~Z-").getList("rows")).as("tenant 1 filtering on tenant 2's prefix").isEmpty();
    }

    @Test
    void aMidEntityCursor_isRefusedWhenAlteredOrReusedElsewhere() {
        String cursor = null;
        cursor = page(1, PAGE, null).get("nextCursor");   // row 200: the middle of the entity
        assertThat(page(1, PAGE, cursor).getList("rows")).hasSize(PAGE);   // valid as issued

        String tampered = cursor.substring(0, cursor.length() - 2)
            + (cursor.endsWith("AA") ? "BB" : "AA");
        for (RequestSpecification bad : List.of(
                render(1, PAGE, tampered),                                  // altered token
                render(1, PAGE - 1, cursor),                                // another page size
                render(1, PAGE, cursor, "label=even"),                      // another filter set
                render(2, PAGE, cursor))) {                                 // another tenant
            bad.when().post("/api/views/render").then().statusCode(400).body("error", is(ViewCursor.REFUSAL));
        }
    }
}
