package io.lumnik.hub.search.projection;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.extraction.spi.search.ProjectionSink;
import io.lumnik.hub.entity.resolution.ResolutionCoverageStore;
import io.lumnik.hub.search.mark.SearchProjectionMarkStore;
import io.lumnik.hub.search.mark.SearchProjectionRunHook;
import io.lumnik.hub.search.mark.SearchProjectionWatermarkStore;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BiFunction;

/** #730: everything the worker needs to tell a {@code ProjectionSink} how fresh a generation is. */
@ApplicationScoped
public class SearchProjectionAsOfAdvancer {

    private static final Logger LOG = Logger.getLogger(SearchProjectionAsOfAdvancer.class);

    @Inject SearchProjectionRunHook runHook;
    @Inject SearchProjectionMarkStore markStore;
    @Inject SearchProjectionWatermarkStore watermarkStore;
    @Inject ResolutionCoverageStore coverageStore;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    /** Test seams (the {@code rowScanner} idiom): default to the real, database-backed lookups. */
    BiFunction<Long, String, Optional<String>> entityOf = this::readEntity;
    BiFunction<Long, String, Optional<Instant>> watermarkOf = this::readWatermark;
    BiFunction<Long, String, ResolutionCoverageStore.UncoveredBound> resolutionBoundOf =
        (tenantId, entityType) -> coverageStore.uncoveredBound(tenantId, entityType);

    /** The rebuild base / advance ceiling: strictly behind every still-open transaction, DB clock. */
    public Instant safeBound() {
        return runHook.safeBoundAdmin();
    }

    /**
     * Freshness advance for one (tenant, index) pair, strictly in this order: fix a bound → read the pair's
     * queue → only if it is empty, tell the sink. The bound itself depends on the entity's mode:
     * {@code match:} takes every scanned source's watermark, {@code resolution:} the earliest uncovered run
     * fence (#748) and the watermark of every lookup of its joins (#850). In both, everything the bound
     * reads MUST be read before the queue check: a producer commits its marks and only then its watermark
     * / coverage row, so a value read after an "empty" verdict could cover marks that check never saw.
     * Returns the bound handed to the sink, or empty when the path stopped (an unknown entity, a source
     * table with no watermark yet, an uncovered run with an unknown fence, a pending mark). A mark
     * inserted after the queue check belongs to a transaction that started after the bound, so it cannot
     * make the bound false; a crash after the sink applied documents but before this call only leaves the
     * stored instant too old.
     */
    public Optional<Instant> advance(long tenantId, String searchIndex, ProjectionSink sink, String liveGeneration) {
        Optional<String> entity = entityOf.apply(tenantId, searchIndex);
        if (entity.isEmpty()) return stopped(tenantId, searchIndex, "no valid declaration");
        // match: first — its one manifest read restores the pre-#748 cost for the common path; only an entity
        // with no scanned source is asked whether it is a resolution: one.
        List<String> tables = runHook.matchSourceTables(entity.get());
        Optional<Instant> bound = !tables.isEmpty() ? matchBound(tenantId, searchIndex, tables)
            : runHook.isResolutionEntity(entity.get()) ? resolutionBound(tenantId, searchIndex, entity.get())
            : stopped(tenantId, searchIndex, "no scanned source");
        if (bound.isEmpty()) return Optional.empty();

        if (markStore.hasPendingMarkAdmin(tenantId, searchIndex)) {
            return stopped(tenantId, searchIndex, "marks still pending");
        }

        sink.advanceAsOf(tenantId, searchIndex, liveGeneration, bound.get());
        return bound;
    }

    /** match: — the #730 rule: safe bound, then every scanned source's watermark. */
    private Optional<Instant> matchBound(long tenantId, String searchIndex, List<String> tables) {
        return capByWatermarks(tenantId, searchIndex, safeBound(), tables);
    }

    /** The bound lowered to each table's watermark; stops when one table has no watermark yet. */
    private Optional<Instant> capByWatermarks(long tenantId, String searchIndex, Instant bound, List<String> tables) {
        for (String table : tables) {
            Optional<Instant> watermark = watermarkOf.apply(tenantId, table);
            if (watermark.isEmpty()) return stopped(tenantId, searchIndex, "no watermark yet for " + table);
            if (watermark.get().isBefore(bound)) bound = watermark.get();
        }
        return Optional.of(bound);
    }

    /**
     * resolution: (#748) — no scanned source (its marks are decision-driven and the view LEFT JOINs
     * undecided rows), so the bound is the safe bound capped by the earliest fence of a run this entity's
     * pass has not covered. An uncovered run with an unknown fence (it predates V60) means no bound at all.
     */
    private Optional<Instant> resolutionBound(long tenantId, String searchIndex, String entity) {
        Instant bound = safeBound();
        ResolutionCoverageStore.UncoveredBound uncovered =
            resolutionBoundOf.apply(tenantId, entity.toLowerCase(Locale.ROOT));
        if (uncovered.unknown()) return stopped(tenantId, searchIndex, "uncovered run with an unknown fence");
        if (uncovered.earliestFence() != null && uncovered.earliestFence().isBefore(bound)) {
            bound = uncovered.earliestFence();
        }
        // #850: a lookup row is written by a run the pass may already have covered; the lookup's watermark advances
        // only after the marks of its scan are committed, so it is the signal that closes that window.
        return capByWatermarks(tenantId, searchIndex, bound, runHook.resolutionLookupTables(entity));
    }

    private static Optional<Instant> stopped(long tenantId, String searchIndex, String why) {
        LOG.debugf("asOf advance skipped for (%d, %s): %s", tenantId, searchIndex, why);
        return Optional.empty();
    }

    private Optional<String> readEntity(long tenantId, String searchIndex) {
        return Jdbc.queryOne(adminDs, "SearchProjectionAsOfAdvancer.readEntity",
            "SELECT entity FROM core.search_index WHERE tenant_id = ? AND name = ? AND status = 'valid'",
            ps -> { ps.setLong(1, tenantId); ps.setString(2, searchIndex); },
            rs -> rs.getString(1));
    }

    private Optional<Instant> readWatermark(long tenantId, String table) {
        try (Connection c = adminDs.getConnection()) {
            return watermarkStore.watermark(c, tenantId, table);
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the watermark of " + table, e);
        }
    }
}
