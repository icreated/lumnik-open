package io.lumnik.hub.ask;

/** The honest answer returned when a query yields 0 rows — a 0-row result is NEVER phrased as a
 *  confident negative (the cardinal sin of enterprise RAG). Held here so the Fidelity OTK can assert
 *  against the exact same text. */
public final class ZeroRowGuard {

  private ZeroRowGuard() {}

  /** The English sentence; the one served follows the request's {@code lang} ({@link AskSentences#zeroRows}). */
  public static final String CAVEAT = AskSentences.zeroRows(null);
}
