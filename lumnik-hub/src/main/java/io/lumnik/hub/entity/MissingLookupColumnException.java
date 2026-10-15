package io.lumnik.hub.entity;

/**
 * A {@code joins[]} entry names a column its lookup table does not have. Raised by the apply
 * preflight, where the lookup is known by construction — a bare 42703 from CREATE VIEW cannot say
 * which table it blames, so it would be a guess.
 */
public class MissingLookupColumnException extends RuntimeException {

  public MissingLookupColumnException(String table, String dbMessage) {
    super("lookup table " + table + ": " + dbMessage
        + " — check joins[].to and the joins[].map keys against the columns its connector creates");
  }
}
