package io.lumnik.hub.connector.jdbc;

import io.lumnik.hub.connector.jdbc.dialect.JdbcDialectRegistry;
import io.lumnik.jdbc.spi.JdbcDialect;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Connection parameters for one source JDBC datasource. Password masked in toString. */
@SuppressWarnings("java:S2068") // field named 'password' is not a hardcoded secret
public record JdbcConnectionConfig(
    String url,
    String user,
    String password,
    String dialectId
) {

  private static final Pattern URL_PATTERN = Pattern.compile("^jdbc:([a-z0-9]+):.*$");

  public static JdbcConnectionConfig parse(String url, String user, String password,
                                           JdbcDialectRegistry registry) {
    if (url == null || !url.startsWith("jdbc:"))
      throw new IllegalArgumentException("Invalid JDBC URL — must start with jdbc:<scheme>://");

    Matcher m = URL_PATTERN.matcher(url);
    if (!m.matches())
      throw new IllegalArgumentException("Invalid JDBC URL format: " + url);

    JdbcDialect dialect = registry.resolveFor(url, null);

    if (user == null || user.isBlank())
      throw new IllegalArgumentException("Missing user");

    return new JdbcConnectionConfig(url, user, password, dialect.id());
  }

  @Override
  public String toString() {
    return "JdbcConnectionConfig[url=" + url + ", user=" + user
        + ", password=***, dialectId=" + dialectId + "]";
  }
}
