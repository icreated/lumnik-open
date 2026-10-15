package io.lumnik.hub.api.sources;

import io.lumnik.hub.connector.jdbc.TableDescriptor;

import java.util.List;
import java.util.UUID;

// S2068: the *Request records below carry a DB 'password' field whose toString() masks the value
// ("password=***") — a field name and a masked debug string, never a hard-coded secret.
@SuppressWarnings("java:S2068")
public class JdbcSourceDtos {

  private static final String USER_FIELD = ", user=";

  // The *Request records carry the source DB password; toString() masks it (url/user kept for debugging).
  public record TestRequest(String url, String user, String password) {
    @Override public String toString() {
      return "TestRequest[url=" + url + USER_FIELD + user + ", password=***]";
    }
  }
  public record TestResponse(boolean ok, String dialect, String version, long latencyMs, List<String> schemas) {}

  /** The source schema a request names; {@code public} when absent or blank. */
  static String schemaOrDefault(String schema) {
    return schema == null || schema.isBlank() ? "public" : schema.strip();
  }

  /**
   * {@code skipPatterns}: <b>null</b> means the source dialect's own default exclusions; an <b>empty list</b>
   * means exclude nothing; a non-empty list replaces the defaults. The CLI sends null unless
   * {@code --skip-pattern} was given.
   */
  public record DiscoverRequest(String url, String user, String password,
                                String schema, List<String> skipPatterns, boolean includeViews) {
    public String schemaOrDefault() { return JdbcSourceDtos.schemaOrDefault(schema); }
    @Override public String toString() {
      return "DiscoverRequest[url=" + url + USER_FIELD + user + ", password=***, schema=" + schema
          + ", skipPatterns=" + skipPatterns + ", includeViews=" + includeViews + "]";
    }
  }
  public record DiscoverResponse(List<TableDescriptor> tables) {}

  public record AddTableRequest(String table, String watermark, String mode) {}
  public record AddRequest(String sourceName, String url, String user, String password, String schema,
                           List<AddTableRequest> tables, String scheduleCron) {
    public String schemaOrDefault() { return JdbcSourceDtos.schemaOrDefault(schema); }
    @Override public String toString() {
      return "AddRequest[sourceName=" + sourceName + ", url=" + url + USER_FIELD + user
          + ", password=***, schema=" + schema + ", tables=" + tables
          + ", scheduleCron=" + scheduleCron + "]";
    }
  }
  public record AddResultEntry(UUID connectorId, String connectorName, String status) {}
  public record AddResponse(List<AddResultEntry> created, List<AddResultEntry> skipped) {}

  public record ListedTable(String table, String connectorName, UUID connectorId,
                            String lastRunAt, long rowsIngested, String status) {}
  public record ListTablesResponse(String sourceName, List<ListedTable> tables) {}
}
