package io.lumnik.hub.connector.mapping.carry;

import io.lumnik.hub.connector.mapping.MappingValidationException;

/** A stored carry that must not be reused: another mapping, another codec version, or none where one is needed. */
public class StaleCarryException extends MappingValidationException {

  /** The gesture, and what it does: the cursor goes, its carry with it, and the source is read again from its start. */
  public static final String RESET = "run `lm endpoint reset-cursor <connector>/<endpoint>` — the next run reads the "
      + "source again from its start";

  public StaleCarryException(String message) { super(message); }
}
