package io.lumnik.hub.entity;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.EntitySchemaBuilder;
import io.lumnik.hub.ask.ReadonlyTransactionsProfile;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * #1338: re-applying an entity whose sources were typed changes its view's column types. A pooled connection that
 * had prepared a read of that view server-side (pgjdbc does after five runs) answers "cached plan must not change
 * result type" once — a 500 per connection after the apply. {@link EntityPlanCacheInvalidator} recycles the pools
 * that read entity views after an apply has committed, and only then.
 */
@QuarkusTest
@TestProfile(ReadonlyTransactionsProfile.class)
class EntityPlanCacheInvalidatorIT {

  @Inject @DataSource("admin") AgroalDataSource adminDs;
  @Inject AgroalDataSource appDs;
  @InjectSpy EntityPlanCacheInvalidator invalidator;
  @Inject EntitySchemaBuilder entityCards;
  @Inject SchemaCardStore cards;

  static final String MANIFEST = "kind: Entity\nname: PcOrder\nmatch: [no]\nscopes: [pc]\nsources:\n"
      + "  - table: t_pc_order\n    map: { no: no, amount: amount }\n";

  static final String VIEW = """
      apiVersion: apps.lumnik.io/v1
      kind: View
      metadata: { name: pc-view }
      spec:
        scope: pc
        table: connector.v_pcorder
        columns: [no, amount]   # bare: the hub reads only true/false as booleans (#1489)
      """;

  private static void exec(AgroalDataSource ds, String... sql) throws Exception {
    try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
      for (String s : sql) st.execute(s);
    }
  }

  @BeforeEach
  void textTypedSource() throws Exception {
    exec(adminDs,
        "DROP VIEW IF EXISTS connector.\"v_pcorder\"",
        "DROP TABLE IF EXISTS connector.t_pc_order",
        "CREATE TABLE connector.t_pc_order (tenant_id bigint NOT NULL, \"no\" text, amount text)",
        "DO $$ BEGIN GRANT SELECT ON connector.t_pc_order TO lumnik_app;"
            + " GRANT SELECT ON connector.t_pc_order TO lumnik_readonly; EXCEPTION WHEN OTHERS THEN NULL; END $$",
        "INSERT INTO connector.t_pc_order VALUES (1, '7', '1500'), (1, '12', '999.5'), (1, '100', '20')",
        "DELETE FROM connector.entity_manifest WHERE name = 'pcorder'");
    apply(MANIFEST).statusCode(200);
  }

  private static ValidatableResponse apply(String manifest) {
    return given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(1)).contentType("application/yaml")
        .body(manifest).post("/api/entities/apply").then();
  }

  private void readBothPools(String expectedFirst) {
    cards.save(1L, "registry", new SchemaCard("pc", entityCards.resolve("pc")));
    for (int i = 0; i < 12; i++) {   // past pgjdbc's prepareThreshold on the connections these reads take
      given().headers(TestIdentity.tenantAndUser(1)).get("/api/entities/pcorder")          // the default pool
          .then().statusCode(200).body("[0].no", equalTo(expectedFirst));
      given().config(YamlBody.CONFIG).header("X-Tenant-Id", "1").contentType("application/yaml").body(VIEW)
          .when().post("/api/views/render")                                                // the readonly pool
          .then().log().ifValidationFails().statusCode(200).body("rows[0].no", equalTo(expectedFirst));
    }
  }

  @Test
  void aReadPreparedBeforeAnApplyThatRetypesTheViewSucceedsAfterIt_inBothPools() throws Exception {
    readBothPools("100");   // text: "100" < "12" < "7"
    exec(adminDs,
        "DROP VIEW connector.\"v_pcorder\"",
        "ALTER TABLE connector.t_pc_order ALTER COLUMN \"no\" TYPE int8 USING \"no\"::int8,"
            + " ALTER COLUMN amount TYPE numeric(18,4) USING amount::numeric");
    apply(MANIFEST).statusCode(200);
    readBothPools("7");     // a number now: 7 < 12 < 100
  }

  @Test
  void aRefusedApplyRecyclesNothing() {
    clearInvocations(invalidator);
    apply("kind: Entity\nname: PcOrder\nmatch: [no]\nsources: nope\n").statusCode(400);
    apply("kind: Entity\nname: PcMissing\nmatch: [no]\nsources:\n  - table: t_pc_absent\n    map: { no: no }\n")
        .statusCode(409);
    verify(invalidator, never()).afterEntityApplied(anyString());   // only a committed apply recycles the pools
    apply(MANIFEST).statusCode(200);
    verify(invalidator, times(1)).afterEntityApplied(anyString());
  }

  /**
   * #1338 review: recycling is best-effort repair after the commit. A pool whose flush fails must not turn a committed
   * apply into a 500 — nor attempt a rollback after the commit — nor keep the other pool from being recycled.
   */
  @Test
  void aPoolThatFailsToFlushLeavesTheApplyCommittedAndSuccessful_andTheOtherPoolRecycled() throws Exception {
    AgroalDataSource failing = Mockito.mock(AgroalDataSource.class);
    AgroalDataSource recording = Mockito.mock(AgroalDataSource.class);
    Mockito.doThrow(new IllegalStateException("pool closed")).when(failing).flush(AgroalDataSource.FlushMode.GRACEFUL);
    Mockito.doReturn(List.of(failing, recording)).when(invalidator).readers();

    String relabelled = MANIFEST.replace("kind: Entity\n", "kind: Entity\nlabel: Commandes recyclees\n");
    apply(relabelled).statusCode(200);

    verify(failing).flush(AgroalDataSource.FlushMode.GRACEFUL);
    verify(recording).flush(AgroalDataSource.FlushMode.GRACEFUL);
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement();
         ResultSet rs = st.executeQuery("SELECT yaml_text FROM connector.entity_manifest WHERE name = 'pcorder'")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString(1)).as("the apply is committed").contains("Commandes recyclees");
    }
    given().headers(TestIdentity.tenantAndUser(1)).get("/api/entities/pcorder").then().statusCode(200);
  }

  /**
   * A connection held while an apply commits is destroyed when it is returned, and idle ones at once — never put back
   * in the pool with the old plan. Measured: how many backends go, and what the next read costs.
   */
  @Test
  void connectionsIdleOrInUseAtTheApplyAreDestroyed_notReturned() throws Exception {
    List<Connection> held = new ArrayList<>();
    List<Integer> pids = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      Connection c = appDs.getConnection();
      held.add(c);
      pids.add(pid(c));
    }
    Connection active = held.remove(0);
    int activePid = pids.get(0);
    for (Connection c : held) c.close();   // three idle in the pool, one still in use

    long t0 = System.nanoTime();
    apply(MANIFEST).statusCode(200);
    long applyMs = (System.nanoTime() - t0) / 1_000_000;

    assertThat(awaitGone(pids.subList(1, 4))).as("idle connections are closed at once").isEmpty();
    assertThat(alive(List.of(activePid))).as("a connection in use is not interrupted").containsExactly(activePid);
    assertThat(pid(active)).as("…and still works").isEqualTo(activePid);
    active.close();
    assertThat(awaitGone(List.of(activePid))).as("…and is destroyed on return, not pooled again").isEmpty();

    long t1 = System.nanoTime();
    given().headers(TestIdentity.tenantAndUser(1)).get("/api/entities/pcorder").then().statusCode(200);
    long firstReadMs = (System.nanoTime() - t1) / 1_000_000;
    System.out.printf("#1338 plan-cache recycle: apply %d ms, %d backends closed, first read after %d ms%n",
        applyMs, pids.size(), firstReadMs);
  }

  private static int pid(Connection c) throws Exception {
    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT pg_backend_pid()")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  /** The pids among {@code pids} that still have a backend, now. */
  private List<Integer> alive(List<Integer> pids) throws Exception {
    List<Integer> alive = new ArrayList<>();
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement("SELECT pid FROM pg_stat_activity WHERE pid = ANY(?)")) {
      ps.setArray(1, c.createArrayOf("int4", pids.toArray()));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) alive.add(rs.getInt(1));
      }
    }
    return alive;
  }

  /** The pids still alive after up to five seconds — a close reaches the server a moment after it returns. */
  private List<Integer> awaitGone(List<Integer> pids) throws Exception {
    List<Integer> alive = alive(pids);
    for (int attempt = 0; attempt < 50 && !alive.isEmpty(); attempt++) {
      Thread.sleep(100);
      alive = alive(pids);
    }
    return alive;
  }
}
