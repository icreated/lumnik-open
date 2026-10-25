package io.lumnik.hub.connector.mapping.hooks.cell;

import io.lumnik.hub.connector.exceptions.AuthoredRunFailure;
import io.lumnik.hub.connector.mapping.MappingValidationException;

/**
 * An operator's Kotlin script threw (#1432): the script, the exception's kind and the script's line — never the
 * exception's message, which can quote the cell the script could not read. The cause keeps it, for the hub's log.
 */
public class ScriptFailedException extends MappingValidationException implements AuthoredRunFailure {
  public ScriptFailedException(String message, Throwable cause) {
    super(message, cause);
  }
}
