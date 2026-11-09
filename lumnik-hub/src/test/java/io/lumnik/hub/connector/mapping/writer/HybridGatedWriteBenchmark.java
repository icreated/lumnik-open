package io.lumnik.hub.connector.mapping.writer;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.PromotedColumn;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.SqlType;
import io.lumnik.hub.event.RowEventGate;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static io.lumnik.hub.testsupport.EventItSupport.execSql;
import static io.lumnik.hub.testsupport.EventItSupport.runInTxAsSystem;
import static io.lumnik.hub.testsupport.EventItSupport.seedRestConnector;

/**
 * #1819: what a chunk of the gated hybrid write costs (a subscription is active). Manual; prints, asserts nothing, runs
 * in no gate. {@code mvn verify -pl lumnik-hub -am -Dtest=NoSuchTest -Dit.test=HybridGatedWriteBenchmark
 * -Dsurefire.failIfNoSpecifiedTests=false -Dbench.rows=20000}. On a laptop with a local database, 20 000 rows: insert
 * 4.6 s, unchanged re-read 6.4 s, changed 6.8 s with one statement per row; 0.9, 0.2-0.9 and 0.8 s with multi-row ones.
 */
@QuarkusTest
class HybridGatedWriteBenchmark {

    private static final long TENANT = 18190L;
    private static final UUID CONNECTOR = UUID.randomUUID();
    private static final UUID ENDPOINT = UUID.randomUUID();

    @Inject HybridJsonbWriter writer;
    @Inject UserTransaction utx;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    @Test
    void time() {
        String table = "ext.bench_" + System.nanoTime();
        seedRestConnector(adminDs, CONNECTOR, TENANT);   // the registry row's FK: a missing one is retried on every write
        Jdbc.update(adminDs, "subscribe", "INSERT INTO core.notification_rule "
            + "(tenant_id, event_pattern, recipient, enabled, entity_filter) VALUES (?, ?, 'bench@x', TRUE, ?)", ps -> {
                ps.setLong(1, TENANT); ps.setString(2, RowEventGate.DATA_ROW_CHANGED); ps.setString(3, table); });
        try {
            HybridJsonbSpec spec = new HybridJsonbSpec(table, "$.id", List.of(
                new PromotedColumn("amount", "$.amount", SqlType.BIGINT, Optional.empty())));
            int n = Integer.getInteger("bench.rows", 20000);
            for (int round = 0; round < 3; round++) {
                for (String phase : List.of("insert", "unchanged", "changed")) {
                    if (round > 0 && phase.equals("insert")) continue;   // keys exist from round 0
                    List<Map<String, Object>> items = new ArrayList<>(), sys = new ArrayList<>();
                    for (int i = 0; i < n; i++) {
                        int amount = phase.equals("changed") ? 2 : 1;
                        items.add(Map.of("id", "K" + i, "amount", amount, "note", "payload-" + i));
                        Map<String, Object> s = new HashMap<>();
                        s.put("_row_hash", phase.equals("changed") ? "c" + round + "-" + i : "h" + i);
                        s.put("_run_id", UUID.randomUUID());
                        sys.add(s);
                    }
                    long t0 = System.nanoTime();
                    runInTxAsSystem(utx, () -> writer.write(spec, items, sys, TENANT, CONNECTOR, ENDPOINT));
                    System.out.printf("BENCH hybrid-gated %-9s round %d rows %d: %d ms%n", phase, round, n,
                        (System.nanoTime() - t0) / 1_000_000);
                }
            }
        } finally {
            execSql(adminDs, "DELETE FROM core.notification_rule WHERE tenant_id = " + TENANT
                + " AND recipient = 'bench@x' AND entity_filter = '" + table + "'");
            execSql(adminDs, "DELETE FROM core.event_outbox WHERE tenant_id = " + TENANT + " AND entity = '" + table + "'");
            execSql(adminDs, "DROP TABLE IF EXISTS " + table);
            execSql(adminDs, "DELETE FROM connector.dynamic_table WHERE connector_id = '" + CONNECTOR + "'");
            execSql(adminDs, "DELETE FROM connector.connector WHERE id = '" + CONNECTOR + "'");
        }
    }
}
