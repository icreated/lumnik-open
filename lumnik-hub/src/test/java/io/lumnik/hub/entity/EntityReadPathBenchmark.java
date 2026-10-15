package io.lumnik.hub.entity;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.rls.RlsEnforcedProfile;
import io.lumnik.hub.testsupport.EntitySourceTableFixture;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Manual benchmark for #868 — wall time THROUGH THE REAL ENDPOINT (auth, RLS, JPA native query,
 * row mapping, JSON serialization, HTTP), not the direct-SQL {@code EXPLAIN} numbers
 * {@code docs/studies/2026-10-08-entity-read-path-study.md} led with. Deliberately named without a
 * {@code Test}/{@code IT} suffix so neither surefire nor failsafe picks it up on a normal
 * {@code mvn test}/{@code verify} — it is slow by design (up to 1,000,000 rows/source) and reports
 * numbers rather than asserting a budget. Run explicitly:
 *
 * <pre>mvn test -pl lumnik-hub -am -Dtest=EntityReadPathBenchmark -Dsurefire.failIfNoSpecifiedTests=false</pre>
 *
 * Same manifest shape as the study's throwaway-Postgres measurements (two fused sources with real
 * conflicts, one join to a 500-row region lookup) so the two sets of numbers are comparable.
 */
@QuarkusTest
@TestProfile(RlsEnforcedProfile.class)
class EntityReadPathBenchmark {

  @Inject
  @DataSource("admin")
  AgroalDataSource adminDs;

  private static final String MANIFEST = """
      kind: Entity
      name: CustomerBench
      match: [customer_id]
      sources:
        - table: t_src_crm
          map: { cust_id: customer_id, name: name, email: email, status: status, region_code: region_code }
        - table: t_src_erp
          map: { id: customer_id, full_name: name, mail: email, state: status, region: region_code }
      joins:
        - table: t_region_lookup
          from: region_code
          to: region_code
          map: { region_name: region_name, country: country }
      """;

  private void seed(int n) throws Exception {
    // create() already drops the view and its own table, in one lock_timeout-protected transaction
    // (EntitySourceTableFixture's own javadoc, #755/#761) — a separate, unprotected DROP here would
    // be both redundant and, at the 100k/1M scales below, exactly where an indefinite stall would
    // hurt most (review, PR #872 — the same finding already fixed once in this PR, for the sibling
    // IT, and reintroduced here by not re-checking this file against it).
    EntitySourceTableFixture.create(adminDs, "t_src_crm", "v_customerbench",
        "cust_id", "name", "email", "status", "region_code");
    EntitySourceTableFixture.create(adminDs, "t_src_erp", "v_customerbench",
        "id", "full_name", "mail", "state", "region");
    EntitySourceTableFixture.create(adminDs, "t_region_lookup", "v_customerbench",
        "region_code", "region_name", "country");

    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      c.setAutoCommit(false);
      st.execute("INSERT INTO connector.t_region_lookup (tenant_id, region_code, region_name, country) "
          + "SELECT 1, i::text, 'Region '||i, (ARRAY['FR','DE','ES','IT','PT'])[1+(i%5)] FROM generate_series(1,500) i");
      st.execute("INSERT INTO connector.t_src_crm (tenant_id, cust_id, name, email, status, region_code) "
          + "SELECT 1, i::text, 'Customer '||i, 'cust'||i||'@x.test', "
          + "CASE WHEN i%20=0 THEN 'inactive' ELSE 'active' END, ((i % 500)+1)::text FROM generate_series(1," + n + ") i");
      st.execute("INSERT INTO connector.t_src_erp (tenant_id, id, full_name, mail, state, region) "
          + "SELECT 1, i::text, 'Customer '||i, 'cust'||i||'@x.test', "
          + "CASE WHEN i%17=0 THEN 'inactive' ELSE 'active' END, ((i % 500)+1)::text FROM generate_series(1," + n + ") i");
      c.commit();
    }
    given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1))
        .contentType("application/yaml").body(MANIFEST).post("/api/entities/apply").then().statusCode(200);
  }

  private long timedGet(String queryString) {
    long start = System.nanoTime();
    Response r = given().headers(TestIdentity.tenantAndUser(1))
        .get("/api/entities/customerbench" + queryString);
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
    assertThat(r.statusCode()).as("GET /api/entities/customerbench%s", queryString).isEqualTo(200);
    return elapsedMs;
  }

  /**
   * One discarded warm-up call, then {@code reps} timed calls, sorted ascending. A single sample
   * cannot tell a real SQL/HTTP gap from cache warm-up or scheduling noise (review, PR #872) —
   * median and min are read off this sorted array by the caller.
   */
  private long[] repeatedTimedGet(String queryString, int reps) {
    timedGet(queryString); // warm-up, discarded
    long[] samples = new long[reps];
    for (int i = 0; i < reps; i++) samples[i] = timedGet(queryString);
    Arrays.sort(samples);
    return samples;
  }

  private static long median(long[] sorted) {
    int mid = sorted.length / 2;
    return sorted.length % 2 == 0 ? (sorted[mid - 1] + sorted[mid]) / 2 : sorted[mid];
  }

  private static String report(String label, long[] sorted) {
    String all = Arrays.stream(sorted).mapToObj(String::valueOf).collect(Collectors.joining(","));
    return String.format("%s: median=%,dms min=%,dms (samples=[%s]ms)", label, median(sorted), sorted[0], all);
  }

  @Test
  void report_wall_time_through_the_real_endpoint_at_every_scale() throws Exception {
    int reps = 3;
    for (int n : new int[] {10_000, 100_000, 1_000_000}) {
      seed(n);
      // Midpoint id for the match-key filter; always exists for every n.
      String midId = String.valueOf(n / 2);

      long[] noFilter = repeatedTimedGet("?limit=100", reps);
      long[] matchKeyFilter = repeatedTimedGet("?customer_id=" + midId + "&limit=100", reps);
      long[] nonMatchFilter = repeatedTimedGet("?status=active&limit=100", reps);

      System.out.printf("#868 ENDPOINT BENCHMARK n=%,d (1 warm-up + %d reps each)%n", n, reps);
      System.out.println("  " + report("no-filter", noFilter));
      System.out.println("  " + report("match-key-filter", matchKeyFilter));
      System.out.println("  " + report("non-match-filter", nonMatchFilter));
    }
  }
}
