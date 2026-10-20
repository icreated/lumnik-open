package io.lumnik.hub.ask;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.AskResult;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.entity.EntityRegistry;
import io.lumnik.hub.entity.EntityViewCompiler;
import io.lumnik.hub.testsupport.EntitySourceTableFixture;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.llm.spi.Analytic;
import io.lumnik.llm.spi.ChatPort;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Manual benchmark for #965 — what a disputed-rows probe costs a whole ask, at scale, under RLS.
 * Deliberately named without a {@code Test}/{@code IT} suffix so a normal {@code mvn test}/{@code verify}
 * never picks it up: it seeds up to 1,000,000 records per source. Run it explicitly, by this exact name
 * (a package glob would pull in the other benchmarks):
 *
 * <pre>mvn test -pl lumnik-hub -am -Dtest=DisputedProbeBenchmark -Dsurefire.failIfNoSpecifiedTests=false</pre>
 *
 * <p>The data is the one #882 measured: two sources, 5 canonical columns, {@code match: [code]},
 * 3.03 % of records disagree on {@code city}, 2 % on {@code segment}; {@code city = 'Lyon'} is 2 % of
 * the records and {@code segment = 'GOLD'} a third of them — the worst case for any pre-filter on the
 * value. Each ask goes through {@link AskToSqlAgent#ask} (model mocked), so it is the read, the probes
 * and the RLS the product pays, not a bare SQL timing.
 *
 * <p>It asserts, at every scale: the compiled probe returns the SAME rows as the probe through the
 * view; every ask still carries its disputed block (a cancelled probe would drop it silently); and the
 * slowest compiled probe, run alone, fits {@code TIMEOUT_S / 2}. The cancel is a cliff, and a case that
 * lands near it passes on a quiet machine and fails on a busy one — half the timeout is the margin.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class DisputedProbeBenchmark {

    @Inject AskToSqlAgent agent;
    @Inject SchemaCardStore store;
    @Inject EntitySchemaBuilder schemas;
    @Inject EntityRegistry registry;
    @Inject EntityViewCompiler compiler;
    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @InjectMock @Analytic ChatPort chatPort;

    private static final String MANIFEST = """
        kind: Entity
        name: ProbeBench
        match: [code]
        scopes: [probebench]
        sources:
          - table: t_pb_erp
            map: { code: code, city: city, segment: segment, name: name, credit: credit }
          - table: t_pb_crm
            map: { customer: code, town: city, tier: segment, company: name }
        """;

    private static final String VIEW = "connector.v_probebench";
    private static final long BUDGET_MS = AskToSqlAgent.TIMEOUT_S * 1000L / 2;

    private void seed(int n) throws Exception {
        EntitySourceTableFixture.create(adminDs, "t_pb_erp", "v_probebench", "code", "city", "segment", "name", "credit");
        EntitySourceTableFixture.create(adminDs, "t_pb_crm", "v_probebench", "customer", "town", "tier", "company");
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            st.execute("INSERT INTO connector.t_pb_erp (tenant_id, code, city, segment, name, credit) "
                + "SELECT 1, 'C-'||i, CASE WHEN i%50=0 THEN 'Lyon' ELSE 'City'||(i%97) END, "
                + "(ARRAY['GOLD','SILVER','BRONZE'])[1+(i%3)], 'Name '||i, (i%500)::text FROM generate_series(1," + n + ") i");
            st.execute("INSERT INTO connector.t_pb_crm (tenant_id, customer, town, tier, company) "
                + "SELECT 1, 'C-'||i, CASE WHEN i%33=0 THEN 'Villeurbanne' WHEN i%50=0 THEN 'Lyon' ELSE 'City'||(i%97) END, "
                + "CASE WHEN i%50=0 THEN 'PLATINUM' ELSE (ARRAY['GOLD','SILVER','BRONZE'])[1+(i%3)] END, "
                + "'Name '||i FROM generate_series(1," + n + ") i");
            c.commit();
            st.execute("ANALYZE connector.t_pb_erp");
            st.execute("ANALYZE connector.t_pb_crm");
        }
        given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
            .contentType("application/yaml").body(MANIFEST).post("/api/entities/apply").then().statusCode(200);
        store.save(1L, "probebench", new SchemaCard("probebench", schemas.resolve("probebench")));
    }

    /** {@code block}: whether the ask reports a disputed record — a model-written LIMIT reports no counted one (#874). */
    private record Ask(String label, String sql, boolean block) {}

    private long timedAsk(Ask a, boolean expectBlock) {
        Mockito.when(chatPort.chat(Mockito.anyString())).thenReturn(a.sql()).thenReturn("answer.");
        long start = System.nanoTime();
        AskResult r = agent.ask(1L, "probebench", "question");
        long ms = (System.nanoTime() - start) / 1_000_000;
        if (expectBlock) assertThat(r.disputed()).as("%s: the disputed block", a.label()).isNotEmpty();
        return ms;
    }

    private long[] repeated(Ask a, int reps, boolean expectBlock) {
        timedAsk(a, expectBlock);   // warm-up, discarded
        long[] samples = new long[reps];
        for (int i = 0; i < reps; i++) samples[i] = timedAsk(a, expectBlock);
        Arrays.sort(samples);
        return samples;
    }

    /** The probe's own rows, through the view and through the relation, on the same plan. */
    private long assertSameRowsAsTheViewProbe(String column, String value, String otherColumn, String otherValue) throws Exception {
        List<DisputedRows.Filter> filters = new ArrayList<>(List.of(new DisputedRows.Filter(column, List.of(value))));
        if (otherColumn != null) filters.add(new DisputedRows.Filter(otherColumn, List.of(otherValue)));
        var plan = new DisputedRows.Plan(VIEW, "probebench", filters, DisputedRows.Counted.IF_ANY_ROW);
        EntityManifest manifest = registry.find("probebench");
        List<String> others = otherColumn == null ? List.of() : List.of(otherColumn);
        long t0 = System.nanoTime();
        List<List<String>> viaView = run(DisputedRows.probe(plan, filters.get(0), List.of("code"), true));
        long viewMs = (System.nanoTime() - t0) / 1_000_000;
        t0 = System.nanoTime();
        List<List<String>> viaRelation = run(DisputedRows.probe(plan, filters.get(0), List.of("code"), true,
            compiler.probeRelation(manifest, sources(manifest), column, others)));
        long relationMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("    same rows? probe through the view %,dms | compiled %,dms | %d rows%n", viewMs, relationMs, viaView.size());
        assertThat(viaRelation).isEqualTo(viaView);
        assertThat(viaView).as("rows to compare").isNotEmpty();
        return relationMs;
    }

    /** The view's own catalog answer (#1185, #1325), as the ask path reads it before a probe. */
    private EntityViewCompiler.Sources sources(EntityManifest m) throws SQLException {
        try (Connection c = adminDs.getConnection()) {
            return EntityViewCompiler.sources(c, m);
        }
    }

    /** The executed plan of the worst case, for the record: where the rows go, which node pays for the ledger. */
    private void printPlan(String column, String value) throws Exception {
        var filter = new DisputedRows.Filter(column, List.of(value));
        var plan = new DisputedRows.Plan(VIEW, "probebench", List.of(filter), DisputedRows.Counted.IF_ANY_ROW);
        EntityManifest bench = registry.find("probebench");
        var probe = DisputedRows.probe(plan, filter, List.of("code"), true,
            compiler.probeRelation(bench, sources(bench), column, List.of()));
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement("EXPLAIN (ANALYZE, COSTS OFF, TIMING OFF) " + probe.sql())) {
            bind(c, ps, probe.params());
            try (ResultSet rs = ps.executeQuery()) {
                System.out.println("    plan of " + column + " = " + value + ":");
                while (rs.next()) System.out.println("      " + rs.getString(1));
            }
        }
    }

    private static void bind(Connection c, PreparedStatement ps, List<Object> params) throws Exception {
        for (int i = 0; i < params.size(); i++) {
            Object p = params.get(i);
            if (p instanceof List<?> l) ps.setArray(i + 1, c.createArrayOf("text", l.toArray()));
            else ps.setString(i + 1, (String) p);
        }
    }

    private List<List<String>> run(DisputedRows.Probe probe) throws Exception {
        List<List<String>> out = new ArrayList<>();
        try (Connection c = adminDs.getConnection(); PreparedStatement ps = c.prepareStatement(probe.sql())) {
            bind(c, ps, probe.params());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(List.of(rs.getString("_k0"), String.valueOf(rs.getString("_retained")), String.valueOf(rs.getString("_ledger"))));
            }
        }
        return out;
    }

    @Test
    void report_what_a_probe_costs_an_ask_at_every_scale() throws Exception {
        List<Ask> asks = List.of(
            new Ask("city = Lyon (2 %)", "SELECT COUNT(*) FROM " + VIEW + " WHERE city = 'Lyon'", true),
            new Ask("segment = GOLD (a third — worst case)", "SELECT COUNT(*) FROM " + VIEW + " WHERE segment = 'GOLD'", true),
            new Ask("city = Lyon AND segment = GOLD", "SELECT COUNT(*) FROM " + VIEW + " WHERE city = 'Lyon' AND segment = 'GOLD'", true),
            new Ask("listing, city = Lyon", "SELECT code, name FROM " + VIEW + " WHERE city = 'Lyon' ORDER BY code LIMIT 500", false));
        for (int n : new int[] {100_000, 1_000_000}) {
            seed(n);
            System.out.printf("#965 PROBE BENCHMARK n=%,d records/source, budget %,dms per ask (TIMEOUT_S/2)%n", n, BUDGET_MS);
            long worst = Math.max(assertSameRowsAsTheViewProbe("city", "Lyon", null, null),
                Math.max(assertSameRowsAsTheViewProbe("segment", "GOLD", null, null),
                    assertSameRowsAsTheViewProbe("city", "Lyon", "segment", "GOLD")));
            assertThat(worst).as("slowest compiled probe at n=%d, against TIMEOUT_S/2", n).isLessThanOrEqualTo(BUDGET_MS);
            if (n == 1_000_000) printPlan("segment", "GOLD");
            for (Ask a : asks) {
                long[] s = repeated(a, 3, a.block());
                long median = s[s.length / 2];
                System.out.printf("  %-40s median=%,dms min=%,dms samples=%s%n", a.label(), median, s[0], Arrays.toString(s));
            }
        }
    }
}
