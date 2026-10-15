package io.lumnik.hub.search.mark;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.applyResolutionEntity;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.createMatchSourceTable;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.dbClock;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.entityManifest;
import static io.lumnik.hub.search.mark.SearchProjectionMarkItSupport.yaml;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class SearchProjectionSafeBoundIT {

    @Inject SearchProjectionRunHook runHook;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    @Test
    void theBoundIsClampedToTheDatabaseClockNeverInstantMax() throws Exception {
        Instant before = dbClock(adminDs);
        Instant bound = runHook.safeBoundAdmin();
        Instant after = dbClock(adminDs);
        // Other test threads may hold a transaction, so only the clamp is asserted: never past the
        // clock, never Instant.MAX (the trap the pre-#730 cap had when nothing was open).
        // The margin applies on this branch too: a transaction starting between the pg_stat_activity
        // snapshot and clock_timestamp() must still be behind the bound.
        assertThat(bound).isBeforeOrEqualTo(after.minusSeconds(1)).isNotEqualTo(Instant.MAX);
        assertThat(bound).isAfter(before.minusSeconds(3600));
    }

    @Test
    void anOpenTransactionHoldsTheBoundAtItsStartMinusTheMargin() throws Exception {
        try (Connection open = adminDs.getConnection()) {
            open.setAutoCommit(false);
            Instant xactStart;
            try (Statement st = open.createStatement();
                 ResultSet rs = st.executeQuery("SELECT now()")) {   // begins the tx; now() = xact start
                rs.next();
                xactStart = rs.getTimestamp(1).toInstant();
            }
            Thread.sleep(50); // the clock moves on; the bound must not
            Instant bound = runHook.safeBoundAdmin();
            assertThat(bound).isBeforeOrEqualTo(xactStart.minusSeconds(1));
            open.rollback();
        }
    }

    @Test
    void matchSourceTablesNamesTheScannedSourcesOfAMatchEntityOnly() throws Exception {
        long tenant = 730_401;
        String match = "AsOfMatch" + System.nanoTime();
        String table = "t_asof_match_" + System.nanoTime();
        createMatchSourceTable(adminDs, table);
        yaml(tenant).body(entityManifest(match, table, List.of("sales"), "code", "name"))
            .post("/api/entities/apply").then().statusCode(200);

        String resolved = "AsOfResolved" + System.nanoTime();
        applyResolutionEntity(adminDs, tenant, resolved, "t_asof_res_" + System.nanoTime());

        assertThat(runHook.matchSourceTables(match)).containsExactly("connector." + table);
        assertThat(runHook.matchSourceTables(resolved)).isEmpty();
        assertThat(runHook.matchSourceTables("NoSuchEntity")).isEmpty();
    }
}
