package io.lumnik.hub.export;

import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.entity.EntityViewCompiler;
import io.lumnik.hub.export.format.ColumnFormatter;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.QuoteMode;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import static io.lumnik.hub.sql.SqlIdentifiers.quoteIdent;

/**
 * Reads a canonical entity's rows through an RLS-scoped connection and streams them out as CSV in
 * bounded batches — {@code FETCH_SIZE} rows held by the JDBC cursor at a time, each written out
 * row-by-row as read — never the whole result set materialized at once. Shared by the one-shot
 * download door ({@code EntitySnapshotDownloadResource}) and, later, the managed-delivery runner
 * ticket; see the design doc for why this class takes no {@code ExportManifest}.
 */
@ApplicationScoped
public class EntitySnapshotStreamer {

    static final int FETCH_SIZE = 500;

    /**
     * columns/orderColumns non-empty, no duplicates, orderColumns ⊆ columns, columns and every
     * filter column ⊆ entity.canonicalColumns(). A violation is a caller bug (IllegalArgumentException), not a
     * request the engine is validating on the request's behalf — see the design doc's "hidden
     * sort key" reasoning for why orderColumns ⊆ columns specifically matters.
     */
    void validate(List<String> columns, List<String> orderColumns, List<ExportManifest.Filter> filter,
                  EntityManifest entity) {
        if (columns == null || columns.isEmpty())
            throw new IllegalArgumentException("columns must not be empty");
        if (orderColumns == null || orderColumns.isEmpty())
            throw new IllegalArgumentException("orderColumns must not be empty");
        Set<String> columnSet = new HashSet<>(columns);
        if (columnSet.size() != columns.size())
            throw new IllegalArgumentException("columns must not contain duplicates");
        if (new HashSet<>(orderColumns).size() != orderColumns.size())
            throw new IllegalArgumentException("orderColumns must not contain duplicates");
        if (!columnSet.containsAll(orderColumns))
            throw new IllegalArgumentException("orderColumns must be a subset of columns");
        Set<String> canonical = new HashSet<>(entity.canonicalColumns());
        if (!canonical.containsAll(columns))
            throw new IllegalArgumentException("columns must be a subset of entity.canonicalColumns()");
        for (ExportManifest.Filter f : filter)
            if (!canonical.contains(f.column()))
                throw new IllegalArgumentException("filter columns must be a subset of entity.canonicalColumns()");
    }

    /**
     * Reads {@code entity}'s rows for the given projection/order through {@code c} (already
     * RLS-scoped by the caller — see the design doc for why this class opens neither the
     * connection nor the transaction) and streams them as CSV in {@code format} into
     * {@code sink}: its delimiter, and a header record only when {@code format.header()} is true.
     * The download door passes {@link ExportManifest.Format#DEFAULT}; a managed run, its
     * manifest's {@code spec.format}. Never closes {@code sink}.
     */
    public SnapshotStats stream(Connection c, EntityManifest entity, SnapshotShape shape,
                                List<String> orderColumns, ExportManifest.Format format,
                                OutputStream sink) throws SQLException, IOException {
        List<String> columns = shape.columns();
        validate(columns, orderColumns, shape.filter(), entity);
        String sql = buildSql(entity, columns, orderColumns, shape.filter());
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < shape.filter().size(); i++)
                ps.setArray(i + 1, c.createArrayOf("text", shape.filter().get(i).values().toArray()));
            ps.setFetchSize(FETCH_SIZE);
            try (ResultSet rs = ps.executeQuery()) {
                return writeCsv(shape.headers(),
                    new FormattingIterator(new ResultSetRowIterator(rs, columns.size()), shape.formatters()),
                    format, sink);
            }
        }
    }

    /**
     * {@code SELECT} the projected columns from {@code connector.v_<entity>}, ordered by
     * {@code orderColumns} then the remaining {@code columns} as tiebreak — one
     * {@code COLLATE "C" NULLS LAST} term per column, no cast (every canonical column is already
     * SQL {@code text} in the view). See the design doc's "Column projection & the deterministic
     * ORDER BY" section for why a single term per column is correct here.
     */
    private String buildSql(EntityManifest entity, List<String> columns, List<String> orderColumns,
                            List<ExportManifest.Filter> filter) {
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(quoteIdent(columns.get(i)));
        }
        sql.append(" FROM ").append(EntityViewCompiler.viewName(entity.name()));

        // spec.filter (#1090): one bound text[] per entry, AND-ed; an equals is a one-value list.
        // A NULL never matches, as in the manifest's own wording.
        for (int i = 0; i < filter.size(); i++) {
            sql.append(i == 0 ? " WHERE " : " AND ")
                .append(quoteIdent(filter.get(i).column())).append(" = ANY(?::text[])");
        }

        List<String> orderAll = new ArrayList<>(orderColumns);
        for (String col : columns) if (!orderColumns.contains(col)) orderAll.add(col);

        sql.append(" ORDER BY ");
        for (int i = 0; i < orderAll.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(quoteIdent(orderAll.get(i))).append(" COLLATE \"C\" NULLS LAST");
        }
        return sql.toString();
    }

    /**
     * Encodes {@code rows} (each already read as one String per {@code header} column, SQL NULL
     * as Java {@code null}) as CSV in {@code format} into {@code sink}: never closes it, only
     * flushes. Counts rows and emitted bytes and computes SHA-256 over exactly those bytes in one
     * pass.
     */
    SnapshotStats writeCsv(List<String> header, Iterator<String[]> rows, ExportManifest.Format format,
                           OutputStream sink) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }

        CountingOutputStream counting = new CountingOutputStream(sink);
        DigestOutputStream digested = new DigestOutputStream(counting, digest);
        Writer writer = new OutputStreamWriter(digested, StandardCharsets.UTF_8);

        CSVFormat.Builder builder = CSVFormat.DEFAULT.builder().setDelimiter(format.delimiter());
        if (format.header()) builder.setHeader(header.toArray(new String[0]));
        CSVFormat csv = builder
            .setQuoteMode(QuoteMode.ALL_NON_NULL)
            .build();

        long rowCount = 0;
        CSVPrinter printer = new CSVPrinter(writer, csv);
        while (rows.hasNext()) {
            printer.printRecord((Object[]) rows.next());
            rowCount++;
        }
        printer.flush(); // never printer.close() — see design doc: close() always closes `sink`

        return new SnapshotStats(rowCount, counting.count(), HexFormat.of().formatHex(digest.digest()));
    }

    /**
     * A byte counter that writes straight to the wrapped stream instead of delegating through
     * {@link FilterOutputStream}'s default {@code write(byte[],int,int)} (which loops through
     * {@code write(int)}) — that default would double-count every byte if this override both
     * incremented {@code count} and called {@code super.write(...)}. See the design doc.
     */
    private static final class CountingOutputStream extends FilterOutputStream {
        private long count;

        CountingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len); // NOT super.write(...) — see class javadoc
            count += len;
        }

        long count() {
            return count;
        }
    }

    /** Applies each column's compiled formatter to its non-null cells; SQL NULL stays NULL. */
    private record FormattingIterator(Iterator<String[]> rows, List<ColumnFormatter> formatters)
            implements Iterator<String[]> {

        @Override
        public boolean hasNext() {
            return rows.hasNext();
        }

        @Override
        public String[] next() {
            String[] row = rows.next();
            for (int i = 0; i < row.length; i++)
                if (row[i] != null) row[i] = formatters.get(i).format(row[i]);
            return row;
        }
    }

    /** Adapts a {@code ResultSet} to {@code Iterator<String[]>} via {@code getString} (every
     *  canonical column is already {@code text} in the view — see the design doc). */
    private static final class ResultSetRowIterator implements Iterator<String[]> {
        private final ResultSet rs;
        private final int columnCount;
        private Boolean hasNextCache;

        ResultSetRowIterator(ResultSet rs, int columnCount) {
            this.rs = rs;
            this.columnCount = columnCount;
        }

        @Override
        public boolean hasNext() {
            if (hasNextCache == null) {
                try {
                    hasNextCache = rs.next();
                } catch (SQLException e) {
                    throw new RuntimeException("EntitySnapshotStreamer: " + e.getMessage(), e);
                }
            }
            return hasNextCache;
        }

        @Override
        public String[] next() {
            if (!hasNext()) throw new NoSuchElementException();
            hasNextCache = null;
            try {
                String[] row = new String[columnCount];
                for (int i = 0; i < columnCount; i++) row[i] = rs.getString(i + 1);
                return row;
            } catch (SQLException e) {
                throw new RuntimeException("EntitySnapshotStreamer: " + e.getMessage(), e);
            }
        }
    }
}
