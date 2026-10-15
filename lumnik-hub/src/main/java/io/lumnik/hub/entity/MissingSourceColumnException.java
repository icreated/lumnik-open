package io.lumnik.hub.entity;

/**
 * A {@code sources[].map} key names a column its source table does not have (a typo, or a column
 * renamed upstream before the re-apply). Raised by the apply preflight, where the source table is
 * known by construction — a bare 42703 from CREATE VIEW cannot say which table it blames (#971).
 */
public class MissingSourceColumnException extends RuntimeException {

  public MissingSourceColumnException(String table, String dbMessage) {
    super("source table " + table + ": " + dbMessage
        + " — check the sources[].map keys against the columns its connector creates");
  }
}
