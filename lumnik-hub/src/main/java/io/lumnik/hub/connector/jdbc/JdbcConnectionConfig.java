package io.lumnik.hub.connector.jdbc;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Connection parameters for one source JDBC datasource. Password masked in toString. */
@SuppressWarnings("java:S2068") // field named 'password' is not a hardcoded secret
public record JdbcConnectionConfig(
    String url,
    String user,
    String password,
    String driverClass,
    String dialectId
) {

  private static final Pattern URL_PATTERN = Pattern.compile("^jdbc:([a-z0-9]+):.*$");

  private static final Map<String, String> DIALECT_BY_SCHEME = Map.of(
      "postgresql", "postgres"
  );

  private static final Map<String, String> DRIVER_BY_SCHEME = Map.of(
      "postgresql", "org.postgresql.Driver"
  );

  public static JdbcConnectionConfig parse(String url, String user, String password) {
    if (url == null || !url.startsWith("jdbc:"))
      throw new IllegalArgumentException("Invalid JDBC URL — must start with jdbc:<scheme>://");

    Matcher m = URL_PATTERN.matcher(url);
    if (!m.matches())
      throw new IllegalArgumentException("Invalid JDBC URL format: " + url);

    String scheme = m.group(1);
    String dialect = DIALECT_BY_SCHEME.get(scheme);
    if (dialect == null)
      throw new IllegalArgumentException("Unsupported dialect '" + scheme
          + "' — v1 supports: postgres");

    String driver = DRIVER_BY_SCHEME.get(scheme);

    if (user == null || user.isBlank())
      throw new IllegalArgumentException("Missing user");

    return new JdbcConnectionConfig(url, user, password, driver, dialect);
  }

  @Override
  public String toString() {
    return "JdbcConnectionConfig[url=" + url + ", user=" + user
        + ", password=***, driverClass=" + driverClass + ", dialectId=" + dialectId + "]";
  }
}
