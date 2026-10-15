package io.lumnik.hub.view;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.testsupport.YamlBody;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ViewRenderResourceIT {

    @Inject SchemaCardStore store;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    private void exec(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @BeforeEach
    void seed() {
        exec("CREATE TABLE IF NOT EXISTS connector.t_view_render_demo "
           + "(customer_code text, name text, segment text)");
        exec("TRUNCATE connector.t_view_render_demo");
        exec("INSERT INTO connector.t_view_render_demo VALUES "
           + "('C-1','Alice','GOLD'), ('C-2','Bob','SILVER')");

        store.save(1L, "registry", new SchemaCard("desk", List.of(
            new SchemaCard.Table("connector.t_view_render_demo", "Desk", List.of(
                new SchemaCard.Column("customer_code", "text", "Code"),
                new SchemaCard.Column("name", "text", "Name"),
                new SchemaCard.Column("segment", "text", "Segment", "Commercial segment.", null,
                        List.of("GOLD", "SILVER")))))));
    }

    private static final String MANIFEST = """
        apiVersion: apps.lumnik.io/v1
        kind: View
        metadata: { name: desk-view }
        spec:
          scope: desk
          table: connector.t_view_render_demo
          title: Desk
          columns: [customer_code, name]
        """;

    private static final String MANIFEST_WITH_SEGMENT =
        MANIFEST.replace("[customer_code, name]", "[customer_code, name, segment]");

    @Test
    void rendersTitleColumnsAndRows() {
        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml").body(MANIFEST)
        .when().post("/api/views/render")
        .then().statusCode(200)
            .body("title", is("Desk"))
            .body("columns.name", contains("customer_code", "name"))
            .body("columns.label", contains("Code", "Name"))
            .body("rows.customer_code", contains("C-1", "C-2"));
    }

    @Test
    void renderCarriesTheScopesLastCompletedRunAsDataAsOf() {
        // P6: the Fiches freshness dial. Same signal as the ask (ScopeFreshness):
        // the scope's last completed run — absent when none, never fabricated.
        exec("DELETE FROM connector.connector_run WHERE tenant_id = 1");
        exec("DELETE FROM connector.connector WHERE tenant_id = 1 AND name = 'src-vr-asof'");
        exec("INSERT INTO connector.connector_mapping (id, tenant_id, name, target_entity, spec_format, spec_content) "
           + "VALUES ('00000000-0000-0000-0000-00000000b6f1', 1, 'm_vr_asof', 'public.x', 'yaml', 'x') "
           + "ON CONFLICT DO NOTHING");
        exec("INSERT INTO connector.connector (id, tenant_id, name, type_id, config, mapping_id, enabled, tags, sync_mode, skip_policy) "
           + "VALUES ('00000000-0000-0000-0000-00000000b6f2', 1, 'src-vr-asof', 'csv-file', '{}'::jsonb, "
           + "'00000000-0000-0000-0000-00000000b6f1', true, ARRAY['desk'], 'Incremental', '{\"type\":\"SkipUpTo\",\"maxSkips\":1}'::jsonb)");
        exec("INSERT INTO connector.connector_run (tenant_id, connector_id, status, sync_mode, triggered_by, started_at, ended_at) "
           + "VALUES (1, '00000000-0000-0000-0000-00000000b6f2', 'Completed', 'Incremental', 'test', "
           + "'2026-08-21T05:00:00Z', '2026-08-21T05:04:00Z')");

        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml").body(MANIFEST)
        .when().post("/api/views/render")
        .then().statusCode(200)
            .body("dataAsOf", startsWith("2026-08-21T05:04"));
    }

    @Test
    void refusesAnUnknownColumn_400() {
        String bad = MANIFEST.replace("[customer_code, name]", "[customer_code, nope]");
        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml").body(bad)
        .when().post("/api/views/render")
        .then().statusCode(400)
            .body("error", containsString("nope"));
    }

    @Test
    void unknownScope_404() {
        String ghost = MANIFEST.replace("scope: desk", "scope: ghost");
        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml").body(ghost)
        .when().post("/api/views/render")
        .then().statusCode(404);
    }

    @Test
    void withoutTenantInContext_401() {
        given().config(YamlBody.CONFIG).contentType("application/yaml").body(MANIFEST)
        .when().post("/api/views/render")
        .then().statusCode(401);
    }

    @Test
    void filtersRowsWithEqualityAndContains() {
        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .queryParam("filter", "name~ali")
            .body(MANIFEST)
        .when().post("/api/views/render")
        .then().statusCode(200)
            .body("rows.customer_code", contains("C-1"));
    }

    @Test
    void refusesAFilterOnAColumnTheViewDoesNotShow_400() {
        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .queryParam("filter", "segment=GOLD")   // MANIFEST does not show segment
            .body(MANIFEST)
        .when().post("/api/views/render")
        .then().statusCode(400)
            .body("error", containsString("segment"));
    }

    @Test
    void refusesAnOutOfDomainValue_400_withTheAllowedList() {
        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .queryParam("filter", "segment=PLATINUM")
            .body(MANIFEST_WITH_SEGMENT)
        .when().post("/api/views/render")
        .then().statusCode(400)
            .body("error", containsString("PLATINUM"))
            .body("error", containsString("GOLD, SILVER"));
    }

    @Test
    void containsOnADomainColumnIsASearchNotAnAssertion() {
        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml")
            .queryParam("filter", "segment~GO")
            .body(MANIFEST_WITH_SEGMENT)
        .when().post("/api/views/render")
        .then().statusCode(200)
            .body("rows.customer_code", contains("C-1"));
    }

    // ── paging (#777) ───────────────────────────────────────────────────────────

    private static final String PAGED = """
        apiVersion: apps.lumnik.io/v1
        kind: View
        metadata: { name: paged-view }
        spec:
          scope: desk
          table: connector.t_view_paged
          title: Paged
          columns: [code, label]
        """;

    private void seedPaged() {
        exec("DROP TABLE IF EXISTS connector.t_view_paged");
        exec("CREATE TABLE connector.t_view_paged (id BIGSERIAL PRIMARY KEY, code text, label text)");
        exec("INSERT INTO connector.t_view_paged (code, label) SELECT 'P-' || lpad(g::text, 2, '0'), "
           + "CASE WHEN g % 2 = 0 THEN 'even' ELSE 'odd' END FROM generate_series(1, 5) g");
        store.save(1L, "registry", new SchemaCard("desk", List.of(
            new SchemaCard.Table("connector.t_view_paged", "Paged", List.of(
                new SchemaCard.Column("code", "text", "Code"),
                new SchemaCard.Column("label", "text", "Label"))))));
    }

    private RequestSpecification render(String manifest) {
        return given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml").body(manifest);
    }

    @Test
    void paged_walkFollowsTheCursorToTheEnd_everyRowOnce() {
        seedPaged();
        var first = render(PAGED).queryParam("pageSize", 2).when().post("/api/views/render")
            .then().statusCode(200)
            .body("rows.code", contains("P-01", "P-02"))
            .body("hasMore", is(true)).body("truncated", is(true)).body("nextCursor", notNullValue())
            .extract();
        String c1 = first.path("nextCursor");
        var second = render(PAGED).queryParam("pageSize", 2).queryParam("cursor", c1).when().post("/api/views/render")
            .then().statusCode(200).body("rows.code", contains("P-03", "P-04")).body("hasMore", is(true)).extract();
        render(PAGED).queryParam("pageSize", 2).queryParam("cursor", (String) second.path("nextCursor"))
            .when().post("/api/views/render")
            .then().statusCode(200).body("rows.code", contains("P-05"))
            .body("hasMore", is(false)).body("truncated", is(false)).body("nextCursor", nullValue());
    }

    @Test
    void unpaged_keepsTheLegacyShape_noCursor() {
        seedPaged();
        render(PAGED).when().post("/api/views/render")
            .then().statusCode(200).body("rows", hasSize(5)).body("hasMore", is(false)).body("nextCursor", nullValue());
    }

    @Test
    void pageSizeOutOfBounds_isRefusedWithTheBounds() {
        seedPaged();
        for (int bad : new int[] {0, -3, 201}) {
            render(PAGED).queryParam("pageSize", bad).when().post("/api/views/render")
                .then().statusCode(400).body("error", allOf(containsString("1"), containsString("200")));
        }
    }

    private String firstCursor(String manifest, int size, String... extraFilter) {
        var req = render(manifest).queryParam("pageSize", size);
        for (String f : extraFilter) req = req.queryParam("filter", f);
        return req.when().post("/api/views/render").then().statusCode(200).extract().path("nextCursor");
    }

    private void assertRefusedAsStale(RequestSpecification req) {
        req.when().post("/api/views/render").then().statusCode(400)
            .body("error", is(ViewCursor.REFUSAL));
    }

    @Test
    void cursor_reusedWithAnotherPageSize_isRefused() {
        seedPaged();
        String c = firstCursor(PAGED, 2);
        assertRefusedAsStale(render(PAGED).queryParam("pageSize", 3).queryParam("cursor", c));
    }

    @Test
    void cursor_reusedWithAnotherFilterSet_isRefused() {
        seedPaged();
        String c = firstCursor(PAGED, 2);
        assertRefusedAsStale(render(PAGED).queryParam("pageSize", 2).queryParam("filter", "label=odd").queryParam("cursor", c));
    }

    @Test
    void cursor_reusedOnAnotherViewOverTheSameTableAndColumns_isRefused() {
        seedPaged();
        String c = firstCursor(PAGED, 2);
        assertRefusedAsStale(render(PAGED.replace("paged-view", "paged-review"))
            .queryParam("pageSize", 2).queryParam("cursor", c));
    }

    @Test
    void cursor_reusedOnAnotherTenant_isRefused() {
        seedPaged();
        String c = firstCursor(PAGED, 2);
        store.save(2L, "registry", new SchemaCard("desk", List.of(
            new SchemaCard.Table("connector.t_view_paged", "Paged", List.of(
                new SchemaCard.Column("code", "text", "Code"),
                new SchemaCard.Column("label", "text", "Label"))))));
        given().config(YamlBody.CONFIG).header("X-Tenant-Id", "2").contentType("application/yaml").body(PAGED)
            .queryParam("pageSize", 2).queryParam("cursor", c)
            .when().post("/api/views/render").then().statusCode(400).body("error", is(ViewCursor.REFUSAL));
    }

    @Test
    void cursor_tamperedOrGarbage_isRefused_andTheRefusalNeverEchoesIt() {
        seedPaged();
        String c = firstCursor(PAGED, 2);
        String tampered = c.substring(0, 5) + (c.charAt(5) == 'A' ? 'B' : 'A') + c.substring(6);
        assertRefusedAsStale(render(PAGED).queryParam("pageSize", 2).queryParam("cursor", tampered));
        assertRefusedAsStale(render(PAGED).queryParam("pageSize", 2).queryParam("cursor", "garbage-token"));
    }

    @Test
    void cursorAlone_withoutAPageSize_isPagedAtTheDefaultSize_andBoundToIt() {
        seedPaged();
        String c = firstCursor(PAGED, 2);
        assertRefusedAsStale(render(PAGED).queryParam("cursor", c));   // sealed for 2, read as the default 50
    }

    @Test
    void paged_withAFilter_walksOnlyTheMatchingRows() {
        seedPaged();
        var first = render(PAGED).queryParam("pageSize", 2).queryParam("filter", "label=odd")
            .when().post("/api/views/render").then().statusCode(200)
            .body("rows.code", contains("P-01", "P-03")).body("hasMore", is(true)).extract();
        render(PAGED).queryParam("pageSize", 2).queryParam("filter", "label=odd")
            .queryParam("cursor", (String) first.path("nextCursor"))
            .when().post("/api/views/render").then().statusCode(200)
            .body("rows.code", contains("P-05")).body("hasMore", is(false));
    }

    @Test
    void aRefusedFilter_onAPagedRead_isStillTheFilterRefusal() {
        seedPaged();
        render(PAGED).queryParam("pageSize", 2).queryParam("filter", "nope=1").when().post("/api/views/render")
            .then().statusCode(400).body("error", containsString("nope"));
    }

    @Test
    void entityView_rowsWithHugeTextValues_stillGetCursorsThatCanBeReused() {
        // An entity view has no id, so the boundary used to carry the whole row: a few kilobytes of
        // description made a cursor the hub itself refused to open again.
        exec("DROP VIEW IF EXISTS connector.v_view_big");
        exec("DROP TABLE IF EXISTS connector.t_view_big_src");
        exec("CREATE TABLE connector.t_view_big_src (code text, notes text)");
        exec("INSERT INTO connector.t_view_big_src SELECT 'B-' || g, repeat('x', 6000) FROM generate_series(1, 4) g");
        exec("CREATE VIEW connector.v_view_big AS SELECT code, notes FROM connector.t_view_big_src");
        store.save(1L, "registry", new SchemaCard("desk", List.of(
            new SchemaCard.Table("connector.v_view_big", "Big", List.of(
                new SchemaCard.Column("code", "text", "Code"),
                new SchemaCard.Column("notes", "text", "Notes"))))));
        String manifest = PAGED.replace("connector.t_view_paged", "connector.v_view_big")
            .replace("[code, label]", "[code, notes]").replace("paged-view", "big-view");

        List<String> codes = new ArrayList<>();
        String cursor = null;
        for (int guard = 0; guard < 10; guard++) {
            RequestSpecification req = render(manifest).queryParam("pageSize", 1);
            if (cursor != null) req = req.queryParam("cursor", cursor);
            var page = req.when().post("/api/views/render").then().statusCode(200).extract();
            codes.addAll(page.path("rows.code"));
            cursor = page.path("nextCursor");
            if (cursor == null) break;
            assertTrue(cursor.length() < 300,
                "a cursor must not carry row text: " + cursor.length() + " chars");
        }
        assertEquals(List.of("B-1", "B-2", "B-3", "B-4"), codes);
    }

    // ── column kind and operators on the wire (#787) ────────────────────────────

    private static final String TYPED = """
        apiVersion: apps.lumnik.io/v1
        kind: View
        metadata: { name: typed-view }
        spec:
          scope: desk
          table: connector.t_view_typed
          title: Typed
          columns: [label, amount, price]
        """;

    private void seedTyped() {
        exec("DROP TABLE IF EXISTS connector.t_view_typed");
        exec("CREATE TABLE connector.t_view_typed (id BIGSERIAL PRIMARY KEY, label text, amount numeric, price money)");
        exec("INSERT INTO connector.t_view_typed (label, amount, price) VALUES ('a', 5, 5), ('b', 50, 50)");
        store.save(1L, "registry", new SchemaCard("desk", List.of(
            new SchemaCard.Table("connector.t_view_typed", "Typed", List.of(
                new SchemaCard.Column("label", "text", "Label"),
                new SchemaCard.Column("amount", "numeric", "Amount"),
                new SchemaCard.Column("price", "money", "Price"))))));
    }

    @Test
    void columns_carryTheHubsKindAndOperators() {
        seedTyped();
        render(TYPED).when().post("/api/views/render")
            .then().statusCode(200)
            .body("columns.kind", contains("text", "number", "text"))
            .body("columns[1].operators", contains("=", ">=", "<=", ">", "<"))
            .body("columns[2].operators", contains("~", "="));   // money: never offered an ordering
    }

    @Test
    void orderingOnAMoneyColumn_isRefusedLoudly_notAZeroRowTable() {
        seedTyped();
        render(TYPED).queryParam("filter", "price>10").when().post("/api/views/render")
            .then().statusCode(400).body("error", allOf(containsString("price"), containsString("money")));
        render(TYPED).queryParam("filter", "amount>10").when().post("/api/views/render")
            .then().statusCode(200).body("rows.label", contains("b"));   // numeric keeps its ordering
    }

    private static final String FLOATS = """
        apiVersion: apps.lumnik.io/v1
        kind: View
        metadata: { name: floats-view }
        spec:
          scope: desk
          table: connector.t_view_floats
          title: Floats
          columns: [label, ratio]
        """;

    @Test
    void orderingOnAFloat_comparesNatively_scientificTextFormsIncluded() {
        exec("DROP TABLE IF EXISTS connector.t_view_floats");
        exec("CREATE TABLE connector.t_view_floats (id BIGSERIAL PRIMARY KEY, label text, ratio double precision)");
        // 1e-05 and 1e+20 print in scientific notation, which the numeric text shape rejects
        exec("INSERT INTO connector.t_view_floats (label, ratio) VALUES ('tiny', 0.00001), ('one', 1), ('huge', 1e20), ('neg', -2.5)");
        store.save(1L, "registry", new SchemaCard("desk", List.of(
            new SchemaCard.Table("connector.t_view_floats", "Floats", List.of(
                new SchemaCard.Column("label", "text", "Label"),
                new SchemaCard.Column("ratio", "double precision", "Ratio"))))));
        render(FLOATS).when().post("/api/views/render")
            .then().statusCode(200).body("columns[1].operators", contains("=", ">=", "<=", ">", "<"));
        render(FLOATS).queryParam("filter", "ratio>0").when().post("/api/views/render")
            .then().statusCode(200).body("rows.label", containsInAnyOrder("tiny", "one", "huge"));
        render(FLOATS).queryParam("filter", "ratio<=1").when().post("/api/views/render")
            .then().statusCode(200).body("rows.label", containsInAnyOrder("tiny", "one", "neg"));
        render(FLOATS).queryParam("filter", "ratio>=0.00001").queryParam("filter", "ratio<1").when().post("/api/views/render")
            .then().statusCode(200).body("rows.label", contains("tiny"));
    }

    // ── scientific notation as an ordering operand (#792) ───────────────────────

    private static final String SCI = """
        apiVersion: apps.lumnik.io/v1
        kind: View
        metadata: { name: sci-view }
        spec:
          scope: desk
          table: connector.t_view_sci
          title: Sci
          columns: [label, ratio, exact, landed]
        """;

    private void seedSci() {
        exec("DROP TABLE IF EXISTS connector.t_view_sci");
        exec("CREATE TABLE connector.t_view_sci (id BIGSERIAL PRIMARY KEY, label text, ratio double precision, "
           + "exact numeric, landed text)");
        // landed is what a CSV delivers: text holding plain numbers
        exec("INSERT INTO connector.t_view_sci (label, ratio, exact, landed) VALUES "
           + "('tiny', 0.00002, 0.00002, '0.00002'), ('one', 1, 1, '1'), ('huge', 6.1e23, 610000000000000000000000, '610000000000000000000000')");
        store.save(1L, "registry", new SchemaCard("desk", List.of(
            new SchemaCard.Table("connector.t_view_sci", "Sci", List.of(
                new SchemaCard.Column("label", "text", "Label"),
                new SchemaCard.Column("ratio", "double precision", "Ratio"),
                new SchemaCard.Column("exact", "numeric", "Exact"),
                new SchemaCard.Column("landed", "text", "Landed"))))));
    }

    @Test
    void scientificBound_worksOnFloatNumericAndTextColumnsAlike() {
        seedSci();
        for (String col : new String[] {"ratio", "exact", "landed"}) {
            render(SCI).queryParam("filter", col + ">=1e-5").queryParam("filter", col + "<1E+23").when().post("/api/views/render")
                .then().statusCode(200).body("rows.label", containsInAnyOrder("tiny", "one"));
            render(SCI).queryParam("filter", col + ">6.0e23").when().post("/api/views/render")
                .then().statusCode(200).body("rows.label", contains("huge"));
        }
    }

    @Test
    void aFloatBoundPostgresCannotHold_isARefusal_notA500() {
        seedSci();
        // subnormals are still doubles: a bound near the very bottom of the range must be served, not refused or a 500
        render(SCI).queryParam("filter", "ratio>1e-320").when().post("/api/views/render")
            .then().statusCode(200).body("rows.label", containsInAnyOrder("tiny", "one", "huge"));
        for (String bad : new String[] {"ratio>1e309", "ratio<1e-400"})
            render(SCI).queryParam("filter", bad).when().post("/api/views/render")
                .then().statusCode(400).body("error", containsString("double precision"));
    }
}
