package io.lumnik.hub.search.projection;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.search.ProjectionDefinition;
import io.lumnik.extraction.spi.search.ProjectionDocument;
import io.lumnik.extraction.spi.search.ProjectionSink;
import io.lumnik.extraction.spi.search.ProviderStatus;
import io.lumnik.hub.entity.EntityRegistry;
import io.lumnik.hub.search.ProjectionSinkGate;
import io.lumnik.hub.search.mark.SearchProjectionMarkStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.lumnik.hub.search.projection.SearchProjectionItSupport.matchEntityYaml;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.searchIndexYaml;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.unique;
import static io.lumnik.hub.search.projection.SearchProjectionItSupport.yaml;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Spec test 18: {@code lumnik.search.provider} reconfigured between two ticks, with TWO real (not
 * mocked) {@link ProjectionSink} candidates installed at once — the real {@link
 * ProjectionSinkGate#resolve()} algorithm choosing between them by name is already an executable
 * specification on its own ({@code ProjectionSinkGateTest}), and spec test 8's "provider mismatch
 * forces a full rebuild regardless of position" is already proven with mocks by {@code
 * SearchProjectionWorkerTest} (Task 11). What neither of those covers — and this test exists
 * specifically to close — is the two glued together end-to-end against real Postgres: a real
 * rebuild persisting {@code sink.name()} into {@code core.search_projection_state.provider_name},
 * a real second tick re-reading that same row, and {@link SearchProjectionWorker}'s own private
 * {@code dispatch} — invoked here exactly as {@code discoveryTick()} would, reflectively only
 * because it is {@code private} — genuinely detecting the mismatch and calling {@code rebuild()}
 * through the NEWLY selected sink, even though position is already at the mark table's head (so a
 * backlog can never be the reason this second rebuild happens).
 *
 * <p><b>How the config is "reconfigured" without a Quarkus restart.</b> {@code
 * ProjectionSinkGate.configuredProvider} is a plain {@code @ConfigProperty}-injected field, set once
 * when the bean is constructed — the same "fixed at construction, not live-reloadable" behavior
 * {@code SearchProjectionWorker.rebuildScanTimeoutSeconds} already has, which every sibling IT in
 * this package already overrides post-construction by hand ({@code workerWith}'s own {@code
 * worker.rebuildScanTimeoutSeconds = 30}). The identical idiom is used here: rather than mutating the
 * REAL, CDI-managed {@code ProjectionSinkGate} singleton the live background scheduler also shares —
 * which would risk that scheduler's own once-a-minute tick racing this test against whichever OTHER
 * tenant's leftover valid SearchIndex rows happen to sit in the shared DevServices Postgres at that
 * moment — a fresh, throwaway {@code ProjectionSinkGate} is constructed directly ({@code new
 * ProjectionSinkGate()}, exactly {@code ProjectionSinkGateTest.gateOf}'s own idiom) and wired to two
 * real, hand-written (not Mockito-mocked) sinks via its {@code providers} field. Only {@code
 * configuredProvider} is reassigned between the two ticks. Both fields are package-private in {@code
 * io.lumnik.hub.search} — a different package from this class, which stays in {@code
 * io.lumnik.hub.search.projection} beside its siblings per the design doc's own file layout — so
 * reflection bridges that one package boundary; nothing else here is reflective except {@code
 * dispatch} itself, for the same private-method reason.
 *
 * <p><b>What this class does NOT cover.</b> {@code discoveryTick()}'s own SQL discovery loop —
 * {@code markStore.validIndexNamesByEntityAndTenantAdmin()}, the cross-tenant admin-pool read that
 * finds every valid {@code (tenant, index)} pair in the first
 * place — is deliberately never exercised here: {@code dispatch()} is called directly, exactly for
 * the reasons above (the real, unscoped {@code discoveryTick()} would iterate every OTHER test's own
 * leftover valid SearchIndex rows in the shared DevServices Postgres). That SQL loop is exercised for
 * real elsewhere — {@code SearchProjectionRunReaperIT} calls the genuine, unscoped {@code
 * discoveryTick()} and handles the exact same shared-DB pollution problem with membership-style
 * assertions (a specific pair IS found among however many others exist) rather than exact-size ones.
 * A future reader should not mistake this class as proving "discovery's SQL finds and correctly
 * routes a provider-mismatched pair" end-to-end — only the gate/config resolution and the dispatch
 * decision it feeds are proven here, genuinely, against real Postgres.
 */
@QuarkusTest
class SearchProjectionProviderSwitchIT {

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject @DataSource("readonly") AgroalDataSource readonlyDs;
    @Inject EntityRegistry registry;
    @Inject SearchProjectionMarkStore markStore;
    @Inject SearchProjectionStateStore stateStore;

    // -- worker construction ------------------------------------------------------------------

    private SearchProjectionWorker workerWith(ProjectionSinkGate gate) {
        SearchProjectionWorker worker = new SearchProjectionWorker(gate, markStore, stateStore);
        worker.adminDs = adminDs;
        worker.readonlyDs = readonlyDs;
        worker.registry = registry;
        worker.rebuildScanTimeoutSeconds = 30;
        // runBuild calls metrics.recordRebuildTerminal unconditionally on every terminal path (Task 4) —
        // a bare SimpleMeterRegistry stands in since this test never asserts on it, same idiom as
        // SearchProjectionItSupport.workerWith.
        worker.metrics = new SearchProjectionMetrics();
        worker.metrics.registry = new SimpleMeterRegistry();
        return worker;
    }

    // -- match: mode fixtures (createMatchTable stays a private copy — see SearchProjectionItSupport's
    // own javadoc for why; unique/yaml/searchIndexYaml/matchEntityYaml are shared from there) --------

    private void createMatchTable(String table) throws Exception {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS connector." + table);
            st.execute("CREATE TABLE connector." + table + " (tenant_id bigint NOT NULL, "
                + "src_code text, src_name text, src_city text)");
            st.execute("ALTER TABLE connector." + table + " ENABLE ROW LEVEL SECURITY");
            st.execute("CREATE POLICY " + table + "_iso ON connector." + table
                + " USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint)");
            st.execute("GRANT SELECT ON connector." + table + " TO lumnik_app");
            st.execute("DO $$ BEGIN GRANT SELECT ON connector." + table + " TO lumnik_readonly;"
                + " EXCEPTION WHEN OTHERS THEN NULL; END $$");
        }
    }

    private void insertMatchRow(String table, long tenant, String code, String name, String city) throws Exception {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO connector." + table + " (tenant_id, src_code, src_name, src_city) VALUES (?, ?, ?, ?)")) {
            ps.setLong(1, tenant);
            ps.setString(2, code);
            ps.setString(3, name);
            ps.setString(4, city);
            ps.executeUpdate();
        }
    }

    private void applyMatchEntity(long tenant, String name, String table) throws Exception {
        createMatchTable(table);
        yaml(tenant).body(matchEntityYaml(name, table, "city")).post("/api/entities/apply").then().statusCode(200);
    }

    private void applySearchIndex(long tenant, String name, String entity, String... fields) {
        yaml(tenant).body(searchIndexYaml(name, entity, fields)).post("/api/search-indexes/apply")
            .then().statusCode(200).body("status", equalTo("valid"));
    }

    // -- gate wiring: a fresh, throwaway ProjectionSinkGate — never the live CDI singleton ---------

    /** Bridges {@code ProjectionSinkGate}'s package-private fields from this sibling package —
     *  {@code ProjectionSinkGateTest}'s own {@code gateOf} idiom, reflective only because of the
     *  package boundary. */
    @SuppressWarnings("unchecked")
    private static ProjectionSinkGate gateOf(String configuredProviderName, ProjectionSink... installed) throws Exception {
        ProjectionSinkGate gate = new ProjectionSinkGate();
        Instance<ProjectionSink> providers = Mockito.mock(Instance.class);
        Mockito.when(providers.iterator()).thenAnswer(inv -> List.of(installed).iterator());
        setField(gate, "providers", providers);
        setField(gate, "configuredProvider", Optional.ofNullable(configuredProviderName));
        return gate;
    }

    /** The "reconfigured between two ticks" step: same gate instance, same real {@code providers}
     *  Instance, only the configured name changes. */
    private static void reconfigure(ProjectionSinkGate gate, String newProviderName) throws Exception {
        setField(gate, "configuredProvider", Optional.ofNullable(newProviderName));
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = ProjectionSinkGate.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** {@code SearchProjectionWorker.dispatch(long, String, ProjectionSink, Optional, boolean)} is
     *  {@code private} — invoked here exactly as {@code discoveryTick()} would for this ONE pair,
     *  rather than calling the real, unscoped {@code discoveryTick()} itself, which reads {@code
     *  markStore.validIndexNamesByEntityAndTenantAdmin()} cross-tenant and would blast through every
     *  OTHER test's own leftover valid SearchIndex rows still sitting in the shared DevServices
     *  Postgres. {@code discoveryTick()} now fetches every pair's state/pending-mark signal once,
     *  ahead of the loop (code review #671, #712) — this reproduces that same pre-fetch for the one
     *  pair under test. */
    private void invokeDispatch(SearchProjectionWorker worker, long tenantId, String index,
                                 ProjectionSink sink) throws Exception {
        Optional<SearchProjectionState> state = stateStore.find(tenantId, index);
        boolean hasPendingMark = markStore.pendingMarkPairsAdmin()
            .getOrDefault(tenantId, Set.of()).contains(index);
        Method dispatch = SearchProjectionWorker.class.getDeclaredMethod(
            "dispatch", long.class, String.class, ProjectionSink.class, Optional.class, boolean.class);
        dispatch.setAccessible(true);
        dispatch.invoke(worker, tenantId, index, sink, state, hasPendingMark);
    }

    // -- the scenario -----------------------------------------------------------------------------

    @Test
    void aProviderSwitchBetweenTwoTicksForcesAFreshRebuildThroughTheNewRealSinkRegardlessOfPosition() throws Exception {
        long tenant = 671_918_001L;
        String entityName = unique("Switch1");
        String table = unique("t_switch1_").toLowerCase(Locale.ROOT);
        applyMatchEntity(tenant, entityName, table);
        insertMatchRow(table, tenant, "c1", "Mira", "Nancy");

        String idxName = unique("switch1-");
        applySearchIndex(tenant, idxName, entityName, "name");
        long expectedWatermark = markStore.maxMarkId(tenant, idxName);
        assertThat(expectedWatermark).as("the apply's own ALL mark exists").isPositive();

        RecordingSink sinkA = new RecordingSink("switch-a");
        RecordingSink sinkB = new RecordingSink("switch-b");
        ProjectionSinkGate gate = gateOf("switch-a", sinkA, sinkB);

        // Tick 1: no state row yet -> a plain rebuild (not the mismatch case under test), through
        // whichever sink the REAL resolve() algorithm currently picks among the two installed.
        assertThat(gate.resolve())
            .as("real config wiring: exactly one name matches among two real, installed candidates")
            .contains(sinkA);

        SearchProjectionWorker worker = workerWith(gate);
        worker.rebuild(tenant, idxName);

        assertThat(sinkA.published).as("tick 1 rebuilds through sinkA").hasSize(1);
        assertThat(sinkB.published).as("sinkB untouched on tick 1").isEmpty();
        Optional<SearchProjectionState> afterTick1 = stateStore.find(tenant, idxName);
        assertThat(afterTick1).isPresent();
        assertThat(afterTick1.get().providerName()).isEqualTo("switch-a");
        assertThat(afterTick1.get().position()).isEqualTo(expectedWatermark);
        // Tick 1's own rebuild already drained every mark at-or-below its watermark (runBuild's own
        // cleanup) — the pair's mark table is empty going into tick 2, well BEHIND position itself
        // (COALESCE(max(id),0) on an empty table is 0 < position). "At the head" here means "no NEW
        // mark has appeared since" — the state's own position vs the CURRENT max is a different,
        // post-cleanup comparison and would wrongly look "behind" (0 < 1).
        long headAfterTick1 = markStore.maxMarkId(tenant, idxName);

        // Reconfigure between ticks — the SAME gate instance, the SAME two real sinks, only the
        // configured name changes.
        reconfigure(gate, "switch-b");
        assertThat(gate.resolve())
            .as("real config wiring picks up the switch — the SAME resolve() call now answers sinkB")
            .contains(sinkB);

        // Nothing advanced the mark table between the two ticks, so the ONLY thing that could
        // possibly force a second rebuild here is the provider mismatch — never a backlog. This is
        // the exact precondition spec test 8's own "regardless of position" clause needs to be
        // meaningful.
        assertThat(markStore.maxMarkId(tenant, idxName))
            .as("no new mark arrived between the two ticks")
            .isEqualTo(headAfterTick1);

        // Tick 2: dispatch() itself — not this test — decides. state.providerName()=="switch-a" no
        // longer equals gate.resolve().get().name()=="switch-b": needsRebuild is true, and dispatch
        // calls rebuild() through the NEWLY resolved sink.
        invokeDispatch(worker, tenant, idxName, gate.resolve().orElseThrow());

        assertThat(sinkB.published)
            .as("dispatch() chose rebuild (not catch-up — catchUp() never calls publish) through the "
              + "newly selected sinkB")
            .hasSize(1);
        assertThat(sinkA.published).as("sinkA is never called again after the switch").hasSize(1);
        Optional<SearchProjectionState> afterTick2 = stateStore.find(tenant, idxName);
        assertThat(afterTick2).isPresent();
        assertThat(afterTick2.get().providerName())
            .as("the state row now reflects the newly selected provider")
            .isEqualTo("switch-b");
        assertThat(afterTick2.get().liveGeneration()).isEqualTo(sinkB.published.get(0));
    }

    // -- fake sink --------------------------------------------------------------------------------

    /** A real (non-mock) hand-written {@link ProjectionSink} — the same "in-memory, substituted by
     *  hand" idiom {@code SearchProjectionFullRebuildIT}'s own {@code RecordingSink} documents,
     *  parameterized by name so two distinct, independently-recording instances can be installed at
     *  once. */
    private static final class RecordingSink implements ProjectionSink {
        private final String name;
        final List<ProjectionDocument> upserted = new CopyOnWriteArrayList<>();
        final List<String> published = new CopyOnWriteArrayList<>();
        final List<String> abandoned = new CopyOnWriteArrayList<>();
        private int generationCounter = 0;

        RecordingSink(String name) { this.name = name; }

        @Override public String name() { return name; }
        @Override public ProviderStatus status() { return null; }

        @Override public synchronized String beginGeneration(long tenantId, String searchIndex, ProjectionDefinition definition) {
            return name + "-gen-" + (++generationCounter);
        }

        @Override public void upsert(long tenantId, String searchIndex, String generation, List<ProjectionDocument> documents) {
            upserted.addAll(documents);
        }

        @Override public void delete(long tenantId, String searchIndex, String generation, List<String> ids) {}

        @Override public void publish(long tenantId, String searchIndex, String generation) {
            published.add(generation);
        }

        @Override public void abandon(long tenantId, String searchIndex, String generation) {
            abandoned.add(generation);
        }

        @Override public void purgeIndex(long tenantId, String searchIndex) {}

        @Override public long liveDocumentCount(long tenantId, String searchIndex) { return 0L; }
    }
}
