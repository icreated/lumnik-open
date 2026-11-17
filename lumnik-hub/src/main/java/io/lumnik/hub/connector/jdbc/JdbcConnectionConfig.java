package io.lumnik.hub.connector.jdbc;

import io.lumnik.common.text.Text;
import io.lumnik.hub.connector.jdbc.dialect.JdbcDialectRegistry;
import io.lumnik.jdbc.spi.JdbcDialect;

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

    // The shape, never the URL (#2002): it can carry a password, and this reaches a 400 body.
    if (!URL_PATTERN.matcher(url).matches())
      throw new IllegalArgumentException("Invalid JDBC URL format — expected jdbc:<scheme>:… with a lower-case scheme");

    JdbcDialect dialect = registry.resolveFor(url, null);

    if (Text.blank(user))
      throw new IllegalArgumentException("Missing user");

    return new JdbcConnectionConfig(url, user, password, dialect.id());
  }

  /**
   * A JDBC URL for a log line (#2002): its scheme alone, {@code jdbc:postgresql:…}. A password may hold any of
   * {@code @ ; ? # /}, so no cut of the rest can be sure to leave none of it on show; the driver's own message names
   * the host.
   */
  public static String forDisplay(String url) {
    if (url == null) return null;
    int scheme = url.indexOf(':', "jdbc:".length());
    return (scheme < 0 ? "jdbc" : url.substring(0, scheme)) + ":…";
  }

  @Override
  public String toString() {
    return "JdbcConnectionConfig[url=" + forDisplay(url) + ", user=" + user
        + ", password=***, dialectId=" + dialectId + "]";
  }
}
