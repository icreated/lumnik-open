package io.lumnik.hub.process;

import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.entity.EntityViewCompiler;
import io.lumnik.hub.event.EventOutboxRow;
import io.lumnik.hub.search.mark.MatchKeyEncoding;
import io.lumnik.hub.sql.TemporalText;
import io.lumnik.hub.sql.TenantRlsScope;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.postgresql.PGStatement;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.lumnik.hub.sql.SqlIdentifiers.quoteIdent;
import static io.lumnik.hub.sql.SqlIdentifiers.quoteTable;

/**
 * The fused row a change in a source table belongs to (#1833): which row it is, then its content.
 *
 * <p><b>Two datasources, on purpose.</b> The key of the changed source row is derived on {@code admin}, with the tenant
 * bound in the predicate — the same way the consumer reads the source row itself. The fused row is read on
 * {@code readonly} under {@link TenantRlsScope}: the Entity view is {@code security_invoker} and carries no
 * {@code tenant_id}, so the tenant's isolation is the RLS of the tables under it. Read as {@code admin}, another tenant's
 * rows holding the same key would be fused into this one's.
 */
@ApplicationScoped
class FusedRowReader {

    @Inject @DataSource("admin") AgroalDataSource adminDs;
    @Inject @DataSource("readonly") AgroalDataSource readonlyDs;

    /** The Entity's catalog facts (history tables, column types) — what the key expressions are built from. */
    EntityViewCompiler.Sources catalog(EntityManifest m) {
        // a block, not an expression: Jdbc.transaction has a void overload the expression would match too
        return Jdbc.transaction(adminDs, "FusedRowReader.catalog", c -> { return EntityViewCompiler.sources(c, m); });
    }

    /** An Entity's view and the columns it is read by: its {@code match:} columns, and the types the view gives them. */
    record FusedView(String view, List<String> matchColumns, List<String> matchTypes) {}

    /**
     * The match tuple of the fused row the source row of {@code row} belongs to, as the view casts it to text; empty when the
     * row belongs to none — a match column that is NULL puts it outside the view.
     *
     * @param keyExprs {@link EntityViewCompiler#matchKeyExpressions} of the event's table
     * @param rowId the hybrid row's id, {@code null} for a dynamic table
     */
    Optional<FusedKey> key(EventOutboxRow row, List<String> keyExprs, String hashColumn, Long rowId) {
        StringBuilder select = new StringBuilder("SELECT ");
        for (int i = 0; i < keyExprs.size(); i++) select.append(i == 0 ? "" : ", ").append(keyExprs.get(i)).append("::text");
        String sql = select + " FROM " + quoteTable(row.getEntity()) + SourceRows.where(hashColumn);
        // a key's text in UTC, as the view casts it (#1325)
        Optional<List<String>> values = Jdbc.transaction(adminDs, "FusedRowReader.key", c -> {
            TemporalText.utcSession(c);
            return Jdbc.queryOne(c, "FusedRowReader.key", sql,
                SourceRows.binder(rowId, row.getTenantId(), row.getEntityKey()), rs -> {
                    List<String> cells = new ArrayList<>();
                    for (int i = 1; i <= keyExprs.size(); i++) cells.add(rs.getString(i));
                    return cells;
                });
        });
        return values.filter(v -> !v.contains(null)).map(FusedKey::new);
    }

    /** A fused row's match tuple, as text; {@link #canonical} is the view's {@code _canonical_key}. */
    record FusedKey(List<String> values) {
        String canonical() { return MatchKeyEncoding.encode(values); }
    }

    /**
     * The read: by the match columns, not by {@code _canonical_key}. The key is an expression of them, so a filter on
     * it reaches each source as a filter on an expression — a sequential scan of every source per event (measured:
     * 99,999 rows removed per source at 100,000 rows). A plain match column is pushed below the view's
     * {@code GROUP BY} into each source, and uses an index on it where there is one ({@code FusedReadPlanIT}).
     * The value is bound as text and cast to the column's type, so the column itself stays uncast.
     */
    static String readSql(FusedView v) {
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(quoteTable(v.view())).append(" WHERE ");
        for (int i = 0; i < v.matchColumns().size(); i++) {
            if (i > 0) sql.append(" AND ");
            sql.append(quoteIdent(v.matchColumns().get(i))).append(" = ?::").append(v.matchTypes().get(i));
        }
        return sql.toString();
    }

    /**
     * The fused rows of {@code keys} — a page of a fan-out — read under {@code tenantId}'s RLS, by canonical key; a key the
     * view has no row for is not in the answer. One connection and one statement shape, as {@link #read}: each key is
     * the single-key read the plan checks cover ({@code FusedReadPlanIT}), never an {@code ANY(?)} the view could not push down.
     */
    Map<String, Map<String, Object>> readAll(FusedView v, List<FusedKey> keys, long tenantId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Map<String, Map<String, Object>> out = new LinkedHashMap<>();
            try (Connection c = readonlyDs.getConnection()) {
                TenantRlsScope.applyTo(c, tenantId);
                // one statement for the page: the same SQL shape, a different key each time
                try (PreparedStatement ps = c.prepareStatement(readSql(v))) {
                    // never a server-named statement: a generic plan would not see the bound key (#966)
                    ps.unwrap(PGStatement.class).setPrepareThreshold(0);
                    for (FusedKey key : keys) {
                        for (int i = 0; i < key.values().size(); i++) ps.setString(i + 1, key.values().get(i));
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) out.put(key.canonical(), SourceRows.toMap(rs));
                        }
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException(Jdbc.failureText("FusedRowReader.readAll", e), e);
            }
            return out;
        });
    }

    /**
     * The fused row of {@code key}, read under {@code tenantId}'s RLS; null when the view has no such row.
     *
     * <p>In a transaction of its own: the event is processed in one whose {@code default} connection is already
     * enlisted, and a second non-XA connection cannot join it. The search projection reads the view the same way.
     */
    Map<String, Object> read(FusedView v, FusedKey key, long tenantId) {
        return readAll(v, List.of(key), tenantId).get(key.canonical());
    }
}
