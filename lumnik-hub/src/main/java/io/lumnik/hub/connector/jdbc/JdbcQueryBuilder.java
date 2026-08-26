package io.lumnik.hub.connector.jdbc;

import io.lumnik.hub.connector.jdbc.dialect.JdbcDialect;

import java.util.List;

/**
 * Pure builder of the SELECT used by JdbcChunkReader. Kept separate so it can be
 * unit-tested without any database.
 */
public class JdbcQueryBuilder {

  public enum Mode { WATERMARK, FULL }

  private final JdbcDialect dialect;

  public JdbcQueryBuilder(JdbcDialect dialect) {
    this.dialect = dialect;
  }

  public String buildSelect(String schema, String table, String watermarkColumn,
                            List<String> pkColumns, JdbcCursor cursor, int chunkSize, Mode mode) {
    String qSchema = dialect.quoteIdentifier(schema);
    String qTable = dialect.quoteIdentifier(table);
    StringBuilder sb = new StringBuilder("SELECT * FROM ").append(qSchema).append(".").append(qTable);

    boolean hasCursor = cursor != null && !cursor.pk().isEmpty();
    if (mode == Mode.WATERMARK && hasCursor) {
      String qWm = dialect.quoteIdentifier(watermarkColumn);
      String qPk = dialect.quoteIdentifier(pkColumns.get(0));
      sb.append(" WHERE ").append(qWm).append(" > ? OR (")
        .append(qWm).append(" = ? AND ").append(qPk).append(" > ?)");
    } else if (mode == Mode.FULL && hasCursor) {
      String qPk = dialect.quoteIdentifier(pkColumns.get(0));
      sb.append(" WHERE ").append(qPk).append(" > ?");
    }

    sb.append(" ORDER BY ");
    if (mode == Mode.WATERMARK && watermarkColumn != null) {
      sb.append(dialect.quoteIdentifier(watermarkColumn)).append(" ASC, ");
    }
    sb.append(dialect.quoteIdentifier(pkColumns.get(0))).append(" ASC");
    sb.append(" LIMIT ").append(chunkSize);
    return sb.toString();
  }
}
