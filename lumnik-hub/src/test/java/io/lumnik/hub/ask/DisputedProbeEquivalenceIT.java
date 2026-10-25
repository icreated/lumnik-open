package io.lumnik.hub.ask;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.entity.EntityRegistry;
import io.lumnik.hub.entity.EntityViewCompiler;
import io.lumnik.hub.sql.TemporalText;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #965 — the compiled probe and the probe through the entity view must return the SAME rows, in the
 * same order, on the same data. The compiled one fuses fewer records; this pins that it fuses them
 * the same way: conflicts, NULLs, empty strings, duplicates inside a source, a source that maps
 * neither the probed column nor all of the key, a composite key, a third source, a lookup beside it,
 * and more disputed records than one probe reads (the cap and the ordering are part of the result).
 */
@QuarkusTest
class DisputedProbeEquivalenceIT {

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject EntityRegistry registry;
    @Inject EntityViewCompiler compiler;

    static final String CUSTOMER = """
        kind: Entity
        name: EqvCust
        match: [code]
        scopes: [eqv]
        sources:
          - table: t_eqv_a
            map: { code: code, city: city, segment: segment }
          - table: t_eqv_b
            map: { customer: code, town: city, tier: segment }
          - table: t_eqv_c
            map: { cid: code, grade: segment }
        """;

    /** #1379: the same three tables, the second source preferred for city only. */
    static final String CUSTOMER_PREFER = CUSTOMER.replace("name: EqvCust", "name: EqvCustPref")
        + "columns:\n  city:\n    prefer: t_eqv_b\n";

    static final String SITE = """
        kind: Entity
        name: EqvSite
        match: [code, site]
        scopes: [eqv]
        sources:
          - table: t_eqv_sa
            map: { code: code, site: site, city: city }
          - table: t_eqv_sb
            map: { customer: code, plant: site, town: city }
        """;

    static final String PRODUCT = """
        kind: Entity
        name: EqvProd
        match: [pid]
        scopes: [eqv]
        sources:
          - table: t_eqv_pa
            map: { pid: pid, cat: cat, name: name }
          - table: t_eqv_pb
            map: { id: pid, label: name }
        joins:
          - table: t_eqv_cat
            from: cat
            to: ck
            map: { title: cat_title }
        """;

    /** #1326 review: a date one source holds as a timestamp and another as a day (midnight UTC). */
    static final String DUE = """
        kind: Entity
        name: EqvDue
        match: [code]
        scopes: [eqv]
        sources:
          - table: t_eqv_da
            map: { code: code, due: due, city: city }
          - table: t_eqv_db
            map: { customer: code, day: due }
        """;

    private boolean seeded;

    private synchronized void seed() throws Exception {
        if (seeded) return;
        exec("DROP VIEW IF EXISTS connector.v_eqvcust", "DROP VIEW IF EXISTS connector.v_eqvsite", "DROP VIEW IF EXISTS connector.v_eqvprod",
             "DROP VIEW IF EXISTS connector.v_eqvdue", "DROP VIEW IF EXISTS connector.v_eqvcustpref", "DROP TABLE IF EXISTS connector.t_eqv_da, connector.t_eqv_db",
             "CREATE TABLE connector.t_eqv_da (code text, due timestamptz, city text)",
             "CREATE TABLE connector.t_eqv_db (customer text, day date)",
             // D1: kept the 6th at 08:00, the day source says the 5th — excluded by due = 2026-07-05.
             // D2: both say the 5th's midnight — no dispute. D3: kept the 6th, the day source says the 5th.
             // D4: kept the 5th at 08:00, the day source says the 5th — the same day, no dispute (#1327).
             "INSERT INTO connector.t_eqv_da VALUES ('D1','2026-07-06 08:00+00','Lyon'), ('D2','2026-07-05 00:00+00','Lyon'),"
                 + " ('D3','2026-07-06 00:00+00','Lyon'), ('D4','2026-07-05 08:00+00','Lyon')",
             "INSERT INTO connector.t_eqv_db VALUES ('D1','2026-07-05'), ('D2','2026-07-05'), ('D3','2026-07-05'),"
                 + " ('D4','2026-07-05')",
             "DROP TABLE IF EXISTS connector.t_eqv_a, connector.t_eqv_b, connector.t_eqv_c, connector.t_eqv_sa, connector.t_eqv_sb,"
                 + " connector.t_eqv_pa, connector.t_eqv_pb, connector.t_eqv_cat",
             "CREATE TABLE connector.t_eqv_a (code text, city text, segment text)",
             // t_eqv_b keeps its row history like a table the hub writes (#1185): P1's town was Caluire in
             // an older run — not the value, and no disagreement, for the view and the compiled probe alike
             "CREATE TABLE connector.t_eqv_b (customer text, town text, tier text, id bigserial, _run_id uuid)",
             "INSERT INTO connector.t_eqv_b (customer, town, tier, _run_id)"
                 + " VALUES ('P1','Caluire','GOLD','00000000-0000-0000-0000-000000001185')",
             "CREATE TABLE connector.t_eqv_c (cid text, grade text)",
             // conflicts, NULLs, empty strings, a duplicate inside one source, a key only one source holds
             "INSERT INTO connector.t_eqv_a VALUES ('P1','Lyon','GOLD'), ('P2','Lyon','GOLD'), ('P3','Paris','SILVER'),"
                 + " ('P4',NULL,'GOLD'), ('P5','Lyon','GOLD'), ('P6','Lyon','GOLD'), ('P6','Lille','GOLD'), ('P7','','GOLD'),"
                 + " ('P8','Lyon',NULL), (NULL,'Lyon','GOLD')",
             "INSERT INTO connector.t_eqv_b VALUES ('P1','Lyon','GOLD'), ('P2','Villeurbanne','GOLD'), ('P3','Lyon','SILVER'),"
                 + " ('P4','Lyon','BRONZE'), ('P5',NULL,'GOLD'), ('P6','Lyon','GOLD'), ('P7','Lyon','GOLD'), ('P9','Lyon','GOLD'),"
                 + " ('P8','Caluire','PLATINUM')",
             "INSERT INTO connector.t_eqv_c VALUES ('P2','PLATINUM'), ('P1','GOLD'), ('P10','GOLD')",
             // 30 more keys than one probe reads: even ones disputed with Lyon retained, odd ones excluded
             "INSERT INTO connector.t_eqv_a SELECT 'Q'||lpad(i::text,2,'0'), CASE WHEN i%2=0 THEN 'Lyon' ELSE 'Paris' END, 'GOLD' FROM generate_series(1,30) i",
             "INSERT INTO connector.t_eqv_b SELECT 'Q'||lpad(i::text,2,'0'), CASE WHEN i%2=0 THEN 'Villeurbanne' ELSE 'Lyon' END, 'GOLD' FROM generate_series(1,30) i",
             "DROP TABLE IF EXISTS connector.t_eqv_sa", "DROP TABLE IF EXISTS connector.t_eqv_sb",
             "CREATE TABLE connector.t_eqv_sa (code text, site text, city text)",
             "CREATE TABLE connector.t_eqv_sb (customer text, plant text, town text)",
             "INSERT INTO connector.t_eqv_sa VALUES ('P1','N','Lyon'), ('P1','S','Lyon'), ('P2','N','Lyon')",
             "INSERT INTO connector.t_eqv_sb VALUES ('P1','N','Lyon'), ('P1','S','Caluire'), ('P2','N','Paris')",
             "CREATE TABLE connector.t_eqv_pa (pid text, cat text, name text)",
             "CREATE TABLE connector.t_eqv_pb (id text, label text)",
             "CREATE TABLE connector.t_eqv_cat (ck text, title text)",
             "INSERT INTO connector.t_eqv_pa VALUES ('X1','c1','Bolt'), ('X2','c2','Nut'), ('X3','c1','Bolt')",
             "INSERT INTO connector.t_eqv_pb VALUES ('X1','Bolt M8'), ('X2','Nut'), ('X3','Bolt')",
             "INSERT INTO connector.t_eqv_cat VALUES ('c1','Fasteners'), ('c2','Fasteners'), ('c2','Hardware')");
        for (String m : List.of(CUSTOMER, CUSTOMER_PREFER, SITE, PRODUCT, DUE)) {
            given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
                .contentType("application/yaml").body(m).post("/api/entities/apply").then().statusCode(200);
        }
        seeded = true;
    }

    record Case(String name, String view, String entity, List<String> keys, List<DisputedRows.Filter> filters, int probed) {
        @Override public String toString() { return name; }
    }

    static DisputedRows.Filter f(String c, String... v) { return new DisputedRows.Filter(c, List.of(v)); }

    /** A filter on a date column, as the plan makes it from the card. */
    static DisputedRows.Filter instants(String c, String... v) { return new DisputedRows.Filter(c, List.of(v), true); }

    static Stream<Arguments> cases() {
        var cust = "connector.v_eqvcust";
        var key = List.of("code");
        List<Case> all = List.of(
            new Case("city = Lyon", cust, "eqvcust", key, List.of(f("city", "Lyon")), 0),
            new Case("city IN (Lyon, Paris)", cust, "eqvcust", key, List.of(f("city", "Lyon", "Paris")), 0),
            new Case("segment = GOLD", cust, "eqvcust", key, List.of(f("segment", "GOLD")), 0),
            new Case("segment = PLATINUM (held by a third source only)", cust, "eqvcust", key, List.of(f("segment", "PLATINUM")), 0),
            new Case("a value no source holds", cust, "eqvcust", key, List.of(f("city", "Atlantis")), 0),
            new Case("the empty string", cust, "eqvcust", key, List.of(f("city", "")), 0),
            new Case("city = Lyon AND segment = GOLD, probing city", cust, "eqvcust", key, List.of(f("city", "Lyon"), f("segment", "GOLD")), 0),
            new Case("city = Lyon AND segment = GOLD, probing segment", cust, "eqvcust", key, List.of(f("city", "Lyon"), f("segment", "GOLD")), 1),
            new Case("city = Lyon AND code IN (P2, P3, Q02)", cust, "eqvcust", key, List.of(f("city", "Lyon"), f("code", "P2", "P3", "Q02")), 0),
            new Case("city = Lyon, the second source preferred for city", "connector.v_eqvcustpref", "eqvcustpref", key,
                List.of(f("city", "Lyon")), 0),
            new Case("city = Lyon AND segment = GOLD, city preferred elsewhere, probing segment", "connector.v_eqvcustpref",
                "eqvcustpref", key, List.of(f("city", "Lyon"), f("segment", "GOLD")), 1),
            new Case("composite key", "connector.v_eqvsite", "eqvsite", List.of("code", "site"), List.of(f("city", "Lyon")), 0),
            new Case("composite key narrowed by one key column", "connector.v_eqvsite", "eqvsite", List.of("code", "site"),
                List.of(f("city", "Lyon"), f("site", "S")), 0),
            new Case("an entity with a lookup, a fused column", "connector.v_eqvprod", "eqvprod", List.of("pid"), List.of(f("name", "Bolt")), 0),
            new Case("an entity with a lookup, a joined column beside", "connector.v_eqvprod", "eqvprod", List.of("pid"),
                List.of(f("name", "Bolt"), f("cat_title", "Fasteners")), 0),
            new Case("an entity with a lookup, probing the joined column", "connector.v_eqvprod", "eqvprod", List.of("pid"),
                List.of(f("cat_title", "Fasteners")), 0),
            new Case("due = 2026-07-05, a date beside a timestamp", "connector.v_eqvdue", "eqvdue", key,
                List.of(instants("due", "2026-07-05")), 0),
            new Case("due = 2026-07-05 AND city = Lyon, probing due", "connector.v_eqvdue", "eqvdue", key,
                List.of(instants("due", "2026-07-05"), f("city", "Lyon")), 0));
        return all.stream().flatMap(c -> Stream.of(Arguments.of(c, true), Arguments.of(c, false)));
    }

    @ParameterizedTest(name = "{0} (counted admissible: {1})")
    @MethodSource("cases")
    void the_compiled_probe_returns_what_the_view_probe_returns(Case c, boolean countedAdmissible) throws Exception {
        seed();
        DisputedRows.Plan plan = new DisputedRows.Plan(c.view(), c.entity(), c.filters(), DisputedRows.Counted.IF_ANY_ROW);
        DisputedRows.Filter f = c.filters().get(c.probed());
        EntityManifest manifest = registry.find(c.entity());
        List<String> others = c.filters().stream().filter(o -> o != f).map(DisputedRows.Filter::column).toList();

        List<List<String>> viaView = run(DisputedRows.probe(plan, f, c.keys(), countedAdmissible), c.keys().size());
        Optional<EntityViewCompiler.ProbeRelation> rel = compiler.probeRelation(manifest, sources(manifest), f.column(), others);
        List<List<String>> viaRelation = run(DisputedRows.probe(plan, f, c.keys(), countedAdmissible, rel), c.keys().size());

        assertThat(viaRelation).isEqualTo(viaView);
        if (c.name().startsWith("due = 2026-07-05")) {
            assertThat(rel).as("a date column is probed through the compiled relation too").isPresent();
            assertThat(viaView.stream().map(r -> r.get(0)).toList())
                .as("the excluded candidates: a source holds the 5th, the record kept another day (D4 kept that day: no dispute)")
                .containsExactly("D1", "D3");
        }
        if (c.name().startsWith("city = Lyon") && c.probed() == 0 && rel.isPresent()) {
            assertThat(viaView).as("the case must read something, or the comparison proves nothing").isNotEmpty();
        }
    }

    @Test
    void the_lookup_beside_a_probe_is_left_to_the_view_and_the_fused_column_is_not() throws Exception {
        seed();
        EntityManifest product = registry.find("eqvprod");
        EntityViewCompiler.Sources sources = sources(product);
        assertThat(compiler.probeRelation(product, sources, "cat_title", List.of())).isEmpty();
        assertThat(compiler.probeRelation(product, sources, "name", List.of("cat_title"))).isEmpty();
        assertThat(compiler.probeRelation(product, sources, "name", List.of())).isPresent();
    }

    @Test
    void the_disagreement_the_comparison_reads_is_really_there() throws Exception {
        // Guards the fixture: a seeding mistake would make both sides empty and the equivalence trivially true.
        seed();
        DisputedRows.Plan plan = new DisputedRows.Plan("connector.v_eqvcust", "eqvcust",
            List.of(f("city", "Lyon")), DisputedRows.Counted.IF_ANY_ROW);
        List<List<String>> rows = run(DisputedRows.probe(plan, plan.filters().get(0), List.of("code"), true), 1);
        assertThat(rows).hasSize(DisputedRows.PROBE_LIMIT);
        assertThat(rows.get(0).get(0)).isNotNull();
    }

    /** The view's own catalog answer (#1185, #1325), as the ask path reads it before a probe. */
    private EntityViewCompiler.Sources sources(EntityManifest m) throws SQLException {
        try (Connection c = adminDs.getConnection()) {
            return EntityViewCompiler.sources(c, m);
        }
    }

    private List<List<String>> run(DisputedRows.Probe probe, int keyCount) throws SQLException {
        List<List<String>> out = new ArrayList<>();
        try (Connection c = adminDs.getConnection()) {
            // As Ask reads: in a transaction, in UTC (TenantRlsScope.applyTo) — a session SET would leak through the pool.
            c.setAutoCommit(false);
            try {
                TemporalText.utcSession(c);
                readInto(c, probe, keyCount, out);
            } finally {
                c.rollback();
                c.setAutoCommit(true);
            }
        }
        return out;
    }

    private static void readInto(Connection c, DisputedRows.Probe probe, int keyCount, List<List<String>> out) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(probe.sql())) {
            for (int i = 0; i < probe.params().size(); i++) {
                Object p = probe.params().get(i);
                if (p instanceof List<?> l) ps.setArray(i + 1, c.createArrayOf("text", l.toArray()));
                else ps.setString(i + 1, (String) p);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    List<String> row = new ArrayList<>();
                    for (int i = 0; i < keyCount; i++) row.add(rs.getString("_k" + i));
                    row.add(rs.getString("_retained"));
                    row.add(rs.getString("_ledger"));
                    out.add(row);
                }
            }
        }
    }

    private void exec(String... sql) throws SQLException {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            for (String s : sql) st.execute(s);
        }
    }
}
