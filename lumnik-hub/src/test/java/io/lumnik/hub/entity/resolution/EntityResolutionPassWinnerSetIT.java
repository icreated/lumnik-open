package io.lumnik.hub.entity.resolution;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.entity.EntityManifestParser;
import io.lumnik.hub.entity.EntityRegistry;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * #904: the pass computes the set of rows to resolve — the newest physical row per identity — ONCE,
 * then pages through it by primary key. It used to re-run the whole {@code DISTINCT ON} over every
 * row past the cursor for every page: Θ(T²/2B) over a source of T physical rows.
 *
 * <p>Two of these tests pin what must NOT change: the exact rows handed to the resolver (latest per
 * identity, each once, in primary-key order — superseded history, a NULL identity and another
 * tenant's rows all left out), and what happens to a row that lands while the pass is running (it is
 * still resolved, after the content it superseded). The third pins the mechanism: how many times the
 * {@code DISTINCT ON} scan runs in one pass, whatever the number of pages.
 *
 * <p>Constructs {@link EntityResolutionPass} directly, like {@code EntityResolutionPassConnectionHoldingIT}
 * (a spy, so the mechanism test can count {@code winnersPast} calls): the resolver is a stub that only
 * records what it was handed; the datasource is the real admin pool.
 */
@QuarkusTest
class EntityResolutionPassWinnerSetIT {

  @Inject EntityRegistry registry;
  @Inject ResolutionCoverageStore coverage;
  CrosswalkStatistics statistics = mock(CrosswalkStatistics.class);
  @Inject @DataSource("admin") AgroalDataSource adminDs;

  private static final String ENTITY = "ResWinIt904";
  private static final String TABLE = "t_res_win_it904_erp";
  private static final long TENANT = 99_041;
  private static final long OTHER_TENANT = 99_042;
  private static final UUID RUN_1 = UUID.randomUUID();
  private static final UUID RUN_2 = UUID.randomUUID();

  private static final String MANIFEST = """
      kind: Entity
      name: ResWinIt904
      sources:
        - table: t_res_win_it904_erp
          map:
            CUSTOMER_ID: customer_id
            EMAIL_COL: email
      resolution:
        identity:
          t_res_win_it904_erp: customer_id
        cascade:
          - id: email
            exact: { fields: [email] }
      """;

  /** {@code customer_id/email} of every row the resolver was handed, in the order it was handed. */
  private final List<String> seen = new CopyOnWriteArrayList<>();

  @BeforeEach
  void setUp() throws Exception {
    try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
      st.execute("DROP TABLE IF EXISTS connector." + TABLE);
      st.execute("CREATE TABLE connector." + TABLE + " ("
          + "id BIGSERIAL PRIMARY KEY, tenant_id bigint NOT NULL, _run_id uuid, "
          + "\"CUSTOMER_ID\" text, \"EMAIL_COL\" text)");
    }
    registry.put(new EntityManifestParser().parse(MANIFEST));
    // Inserted in this order, so ids 1..12. Winners for TENANT, by id: c2 (3), c1 (5), c3 (7), c4 (8),
    // c5 (10), c6 (12) — six rows, three pages at batch size 2.
    insert(TENANT, RUN_1, "c1", "a1");          // 1   superseded by 2, then 5
    insert(TENANT, RUN_1, "c1", "a2");          // 2
    insert(TENANT, RUN_1, "c2", "b1");          // 3   c2's only row
    insert(TENANT, RUN_1, "c3", "x1");          // 4   superseded by 7 (another run)
    insert(TENANT, RUN_1, "c1", "a3");          // 5   c1's latest
    insert(TENANT, RUN_2, null, "n1");          // 6   no identity value: never resolvable
    insert(TENANT, RUN_2, "c3", "x2");          // 7   c3's latest
    insert(TENANT, RUN_2, "c4", "d1");          // 8
    insert(OTHER_TENANT, RUN_2, "c1", "other"); // 9   another tenant's c1: never seen
    insert(TENANT, RUN_2, "c5", "e1");          // 10
    insert(TENANT, RUN_2, "c6", "f1");          // 11  superseded within the same run
    insert(TENANT, RUN_2, "c6", "f2");          // 12  c6's latest
  }

  @Test
  void replay_hands_the_resolver_exactly_the_latest_row_per_identity_once_in_pk_order() {
    pass(2, record()).runReplay(TENANT, ENTITY);

    assertThat(seen).containsExactly("c2/b1", "c1/a3", "c3/x2", "c4/d1", "c5/e1", "c6/f2");
  }

  @Test
  void incremental_hands_the_resolver_exactly_the_latest_row_per_identity_among_the_runs_own_rows() {
    EntityManifest m = registry.findReloadingIfMissing(ENTITY);

    pass(2, record()).resolveEntityIncremental(TENANT, m, RUN_2, null);

    // c1's rows all belong to RUN_1; c3's RUN_2 row is its latest; c6 keeps only f2.
    assertThat(seen).containsExactly("c3/x2", "c4/d1", "c5/e1", "c6/f2");
  }

  @Test
  void the_latest_row_per_identity_is_computed_once_per_pass_not_once_per_page() {
    EntityResolutionPass p = pass(2, record());

    p.runReplay(TENANT, ENTITY);

    assertThat(seen).as("three pages of two rows").hasSize(6);
    // One DISTINCT ON for the whole set, plus one that finds nothing landed meanwhile — never one per page.
    verify(p, times(2)).winnersPast(anyString(), any(), any(), anyLong());
  }

  @Test
  void the_crosswalk_statistics_are_looked_after_once_per_page() {
    EntityResolutionPass p = pass(2, record());

    p.runReplay(TENANT, ENTITY);

    verify(statistics, times(3)).analyseIfNeverAnalysed();   // three pages of two rows
  }

  @Test
  void a_row_landing_during_the_pass_is_still_resolved_after_the_content_it_supersedes() {
    // While the first row is being resolved, c1 (in the first page) and c4 (a later page) change, and
    // c9 appears — a run committing into the table while a replay is half-way through it.
    CascadeResolver landing = new CascadeResolver() {
      @Override
      public ResolutionResult resolve(long tenantId, String entityType, String source, String sourceId,
          List<EntityManifest.CascadeRule> cascade, Map<String, String> raw, UUID runId) {
        if (seen.isEmpty()) {
          try {
            insert(TENANT, RUN_2, "c1", "a4");
            insert(TENANT, RUN_2, "c4", "d2");
            insert(TENANT, RUN_2, "c9", "z1");
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        }
        return record().resolve(tenantId, entityType, source, sourceId, cascade, raw, runId);
      }
    };

    pass(2, landing).runReplay(TENANT, ENTITY);

    Map<String, String> last = new LinkedHashMap<>();
    for (String s : seen) last.put(s.substring(0, s.indexOf('/')), s);
    assertThat(last).as("the last resolution of every identity reads its newest row")
        .containsOnly(
            Map.entry("c1", "c1/a4"), Map.entry("c2", "c2/b1"), Map.entry("c3", "c3/x2"),
            Map.entry("c4", "c4/d2"), Map.entry("c5", "c5/e1"), Map.entry("c6", "c6/f2"),
            Map.entry("c9", "c9/z1"));
  }

  // ---------------------------------------------------------------------------------------------

  private EntityResolutionPass pass(int batchSize, CascadeResolver resolver) {
    EntityResolutionPass p = spy(new EntityResolutionPass());
    p.registry = registry;
    p.coverage = coverage;   // runReplay snapshots and covers runs (#748): the real store, its own connections
    p.batchSize = batchSize;
    p.adminDs = adminDs;
    p.statistics = statistics;
    p.resolver = resolver;
    return p;
  }

  /** A resolver that records {@code customer_id/email} and decides nothing. */
  private CascadeResolver record() {
    return new CascadeResolver() {
      @Override
      public ResolutionResult resolve(long tenantId, String entityType, String source, String sourceId,
          List<EntityManifest.CascadeRule> cascade, Map<String, String> raw, UUID runId) {
        seen.add(sourceId + "/" + raw.get("email"));
        return new ResolutionResult(EntityResolutionState.RESOLVED, UUID.randomUUID(), true, 0, 0, List.of());
      }
    };
  }

  private void insert(long tenant, UUID run, String customerId, String email) throws Exception {
    try (Connection c = adminDs.getConnection();
         PreparedStatement ps = c.prepareStatement("INSERT INTO connector." + TABLE
             + " (tenant_id, _run_id, \"CUSTOMER_ID\", \"EMAIL_COL\") VALUES (?, ?, ?, ?)")) {
      ps.setLong(1, tenant);
      ps.setObject(2, run);
      ps.setString(3, customerId);
      ps.setString(4, email);
      ps.executeUpdate();
    }
  }
}
